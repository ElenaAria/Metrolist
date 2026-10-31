package ultra.feature.dsp.engine

import java.util.concurrent.atomic.AtomicLong

/** What a deck is doing during one block. */
enum class DeckRole {
    /** Not in a transition: audio passes through untouched (bit-exact). */
    SOLO,

    /** The track being faded out by upstream's crossfade. */
    OUTGOING,

    /** The track being faded in. */
    INCOMING,
}

/**
 * What the engine tells a deck to do with one block of audio. Owned by its [DjDeck] and mutated only
 * while the engine lock is held and only on that deck's own audio thread, which is also the only
 * reader, so it needs no further synchronization and allocates nothing per block.
 */
class DeckPlan internal constructor() {
    var role: DeckRole = DeckRole.SOLO
        internal set

    /** Transition progress at the first and last frame of the block, 0..1. */
    var progressStart: Float = 0f
        internal set
    var progressEnd: Float = 0f
        internal set

    var profile: DjProfile = DjProfiles.forId(ProfileId.WIRED)
        internal set
}

/**
 * The live inputs of the engine, read on every block. [active] already folds in "the user enabled the
 * engine" and "upstream's crossfade is on", so the engine has no knowledge of either.
 */
data class DjSnapshot(
    val active: Boolean,
    val profile: DjProfile,
    val transitionMs: Int,
) {
    companion object {
        val OFF = DjSnapshot(active = false, profile = DjProfiles.forId(ProfileId.WIRED), transitionMs = 5_000)
    }
}

data class DjStats(
    val decksAlive: Int,
    val mixing: Boolean,
    /** Progress of the incoming deck, 0..1, or 0 when not mixing. */
    val transitionProgress: Float,
    val transitionsMixed: Int,
    /** Length of the most recent completed transition in ms, 0 if none yet. */
    val lastTransitionMs: Long,
    /** Blocks in which the soft ceiling had to bend at least one peak. */
    val peakGuardBlocks: Long,
)

/**
 * Coordinates the decks (one per audio sink) and decides, block by block, who is fading out and who is
 * fading in.
 *
 * ## How an overlap is detected without a player hook
 * Upstream's crossfade builds a second ExoPlayer, and therefore a second audio sink, when the fade
 * starts, then ramps the two players' volumes. Both sinks run our processors. So "two decks are
 * feeding audio at the same time and one of them has only just started" is exactly "a crossfade is
 * starting": the older deck is the outgoing track, the newer one the incoming track. The engine never
 * sees the volume ramp (it is applied after the processors) so it times the transition in audio frames
 * fed since the overlap began, which advances at playback speed and also freezes while paused.
 *
 * Conservative by construction: a transition needs the engine to be active, an outgoing deck that has
 * already been playing for a while and fed very recently, and an incoming deck that has only just
 * started. A deck can be incoming once and outgoing once per session, so the same pair can never
 * trigger twice. Anything else (seeks, skips, pauses, a lone deck) leaves everything on [DeckRole.SOLO].
 *
 * Thread-safety: every deck calls in from its own ExoPlayer playback thread. All mutable state is
 * guarded by one lock held only for counter arithmetic (microseconds), never during DSP.
 */
class DjEngine(
    private val snapshot: () -> DjSnapshot,
    /** Playback-time gap after which a deck no longer counts as "feeding right now". */
    private val staleMs: Int = DEFAULT_STALE_MS,
    /** Playback-time gap after which an outgoing deck counts as gone while the incoming one plays on. */
    private val outgoingGoneMs: Int = DEFAULT_OUTGOING_GONE_MS,
) {
    private val lock = Any()
    private val decks = ArrayList<DjDeck>()
    private var nextId = 1
    private var transition: Transition? = null
    private var transitionsMixed = 0
    private var lastTransitionMs = 0L
    private val peakGuardBlocks = AtomicLong()

    private class Transition(
        val outgoing: DjDeck,
        val incoming: DjDeck,
        val outStartFrames: Long,
        val inStartFrames: Long,
        val lengthMs: Int,
    )

    /** One deck per audio sink. Cheap; the sink calls this once when it is built. */
    fun newDeck(): DjDeck =
        synchronized(lock) {
            prune()
            DjDeck(this, nextId++).also {
                it.registered = true
                decks += it
            }
        }

    fun stats(): DjStats =
        synchronized(lock) {
            val t = transition
            DjStats(
                decksAlive = decks.count { it.alive },
                mixing = t != null,
                transitionProgress = t?.let { progress(it.incoming, it.inStartFrames, it.lengthMs).toFloat() } ?: 0f,
                transitionsMixed = transitionsMixed,
                lastTransitionMs = lastTransitionMs,
                peakGuardBlocks = peakGuardBlocks.get(),
            )
        }

    internal fun recordPeakGuard() {
        peakGuardBlocks.incrementAndGet()
    }

    internal fun beginBlock(
        deck: DjDeck,
        frames: Int,
        sampleRate: Int,
    ) {
        val snap = snapshot()
        synchronized(lock) {
            if (!deck.registered) {
                decks += deck
                deck.registered = true
            }
            if (!deck.alive) deck.startSession(playbackNow())
            deck.sampleRate = sampleRate
            // Stamp the deck at the end of the block it is about to play. Playback time is the greatest of
            // these stamps, so it advances only as audio is fed: a pause (nothing fed) freezes it and the
            // transition survives, while a deck that stops being fed falls behind and counts as gone.
            deck.lastFeedNs = playbackNow() + frames

            transition?.let { if (shouldEnd(it, playbackNow(), snap)) endTransition(it) }
            if (transition == null && snap.active) tryStart(deck, playbackNow(), snap)

            val plan = deck.plan
            plan.profile = snap.profile
            val t = transition
            when {
                t != null && deck === t.incoming -> fill(plan, DeckRole.INCOMING, deck, t.inStartFrames, t.lengthMs, frames)
                t != null && deck === t.outgoing -> fill(plan, DeckRole.OUTGOING, deck, t.outStartFrames, t.lengthMs, frames)
                else -> {
                    plan.role = DeckRole.SOLO
                    plan.progressStart = 0f
                    plan.progressEnd = 0f
                }
            }
            deck.framesFed += frames
        }
    }

    internal fun deckEnded(deck: DjDeck) =
        synchronized(lock) {
            deck.alive = false
            endIfInvolved(deck)
        }

    internal fun deckReset(deck: DjDeck) =
        synchronized(lock) {
            deck.alive = false
            deck.framesFed = 0
            deck.lastFeedNs = 0
            endIfInvolved(deck)
        }

    private fun endIfInvolved(deck: DjDeck) {
        transition?.let { if (it.incoming === deck || it.outgoing === deck) endTransition(it) }
    }

    /** The playback instant "now": the furthest any deck has been fed, which only moves as audio arrives. */
    private fun playbackNow(): Long = decks.maxOfOrNull { it.lastFeedNs } ?: 0L

    private fun tryStart(
        deck: DjDeck,
        now: Long,
        snap: DjSnapshot,
    ) {
        if (deck.usedAsIncoming) return
        if (deck.framesFed > framesFor(MAX_INCOMING_AGE_MS, deck.sampleRate)) return

        val outgoing =
            decks
                .filter {
                    it !== deck &&
                        it.alive &&
                        !it.usedAsOutgoing &&
                        it.firstFeedNs < deck.firstFeedNs &&
                        now - it.lastFeedNs <= framesFor(staleMs, it.sampleRate) &&
                        it.framesFed >= framesFor(MIN_OUTGOING_PLAYED_MS, it.sampleRate)
                }.maxByOrNull { it.framesFed } ?: return

        outgoing.usedAsOutgoing = true
        deck.usedAsIncoming = true
        transition =
            Transition(
                outgoing = outgoing,
                incoming = deck,
                outStartFrames = outgoing.framesFed,
                inStartFrames = deck.framesFed,
                lengthMs = snap.transitionMs.coerceIn(MIN_TRANSITION_MS, MAX_TRANSITION_MS),
            )
    }

    private fun shouldEnd(
        t: Transition,
        now: Long,
        snap: DjSnapshot,
    ): Boolean {
        if (!snap.active) return true
        if (!t.outgoing.alive || !t.incoming.alive) return true
        if (progress(t.incoming, t.inStartFrames, t.lengthMs) >= 1.0) return true
        // The outgoing sink went quiet while the incoming one keeps playing: the fade is over. If both are
        // quiet the user simply paused, and the transition must survive that.
        return now - t.outgoing.lastFeedNs > framesFor(outgoingGoneMs, t.outgoing.sampleRate) &&
            now - t.incoming.lastFeedNs <= framesFor(staleMs, t.incoming.sampleRate)
    }

    private fun endTransition(t: Transition) {
        if (progress(t.incoming, t.inStartFrames, t.lengthMs) >= COMPLETED_PROGRESS) {
            transitionsMixed++
            lastTransitionMs = (t.incoming.framesFed - t.inStartFrames) * 1000L / t.incoming.sampleRate.coerceAtLeast(1)
        }
        transition = null
    }

    private fun fill(
        plan: DeckPlan,
        role: DeckRole,
        deck: DjDeck,
        startFrames: Long,
        lengthMs: Int,
        frames: Int,
    ) {
        val lengthFrames = framesFor(lengthMs, deck.sampleRate).toDouble()
        val p0 = ((deck.framesFed - startFrames) / lengthFrames).coerceIn(0.0, 1.0)
        val p1 = ((deck.framesFed + frames - startFrames) / lengthFrames).coerceIn(0.0, 1.0)
        plan.role = role
        plan.progressStart = p0.toFloat()
        plan.progressEnd = p1.toFloat()
    }

    private fun progress(
        deck: DjDeck,
        startFrames: Long,
        lengthMs: Int,
    ): Double = ((deck.framesFed - startFrames) / framesFor(lengthMs, deck.sampleRate).toDouble()).coerceIn(0.0, 1.0)

    private fun framesFor(
        ms: Int,
        sampleRate: Int,
    ): Long = (ms.toLong() * sampleRate.coerceAtLeast(1) / 1000L).coerceAtLeast(1L)

    /** Drops decks that finished and are not part of the running transition. New decks are never pruned. */
    private fun prune() {
        if (decks.size < PRUNE_ABOVE) return
        val t = transition
        decks.removeAll { deck ->
            val drop = !deck.alive && deck !== t?.incoming && deck !== t?.outgoing
            if (drop) deck.registered = false
            drop
        }
    }

    companion object {
        const val MIN_TRANSITION_MS = 500
        const val MAX_TRANSITION_MS = 30_000

        /** A deck must have played this long to count as the outgoing track (rules out start-up and seeks). */
        const val MIN_OUTGOING_PLAYED_MS = 3_000

        /** A deck older than this cannot be the incoming track. */
        const val MAX_INCOMING_AGE_MS = 10_000

        private const val COMPLETED_PROGRESS = 0.9
        private const val PRUNE_ABOVE = 6

        private const val DEFAULT_STALE_MS = 750
        private const val DEFAULT_OUTGOING_GONE_MS = 5_000
    }
}

/**
 * One audio sink's handle on the [DjEngine]. Fields are guarded by the engine's lock; the only part
 * the owning audio thread touches without it is [plan], which only that thread reads.
 */
class DjDeck internal constructor(
    private val engine: DjEngine,
    val id: Int,
) {
    internal var registered = false
    internal var alive = false
    internal var framesFed = 0L
    internal var lastFeedNs = 0L
    internal var firstFeedNs = 0L
    internal var sampleRate = 48_000
    internal var usedAsIncoming = false
    internal var usedAsOutgoing = false

    internal val plan = DeckPlan()

    internal fun startSession(nowNs: Long) {
        alive = true
        framesFed = 0
        firstFeedNs = nowNs
        usedAsIncoming = false
        usedAsOutgoing = false
    }

    /** Called with every block about to be processed; returns what to do with it. */
    fun beginBlock(
        frames: Int,
        sampleRate: Int,
    ): DeckPlan {
        engine.beginBlock(this, frames, sampleRate)
        return plan
    }

    /** The soft ceiling bent a peak in the block just processed. */
    fun reportPeakGuard() = engine.recordPeakGuard()

    /** The sink queued end of stream. */
    fun onEndOfStream() = engine.deckEnded(this)

    /** The sink was reset/released: the session is over. */
    fun onReset() = engine.deckReset(this)
}
