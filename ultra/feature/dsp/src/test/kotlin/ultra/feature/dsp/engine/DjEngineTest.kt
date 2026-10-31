package ultra.feature.dsp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The engine with a fake clock and a controllable snapshot, so every scenario is deterministic.
 * A "block" is 960 frames at 48 kHz (20 ms), which is what an ExoPlayer audio sink typically feeds.
 */
class DjEngineTest {
    private var active = true
    private var transitionMs = 5_000
    // Fresh per test: the engine is stateful, and the tests must not see each other's decks.
    private lateinit var engine: DjEngine

    @Before
    fun setUp() {
        // The windows are longer than the longest single feed here (12 s), so a deck is never judged stale
        // just because the test fed the other deck a lot in one go. The app's defaults are far shorter.
        engine =
            DjEngine(
                snapshot = { DjSnapshot(active, DjProfiles.forId(ProfileId.WIRED), transitionMs) },
                // Longer than the longest feed in these tests (29 s), so a transition is never cut short just
                // because the test fed one deck a lot in one go. The app's defaults are far shorter.
                staleMs = 40_000,
                outgoingGoneMs = 40_000,
            )
    }

    private fun deck() = engine.newDeck()

    /**
     * Feeds [blocks] blocks of 20 ms, with the clock advancing in real time alongside the audio, the way a
     * playing sink behaves. A deck that is not being fed keeps its old timestamp, so it falls behind and
     * eventually counts as silent.
     */
    private fun feed(
        deck: DjDeck,
        blocks: Int,
    ): DeckPlan {
        var plan = deck.beginBlock(0, RATE)
        repeat(blocks) { plan = deck.beginBlock(BLOCK_FRAMES, RATE) }
        return plan
    }

    private fun role(
        deck: DjDeck,
        blocks: Int = 1,
    ) = feed(deck, blocks).role

    @Test
    fun soloDeckIsAlwaysSolo() {
        val a = deck()
        repeat(5) { assertEquals(DeckRole.SOLO, role(a, blocks = 100)) }
    }

    @Test
    fun overlapOfALongPlayingDeckAndANewOneStartsATransition() {
        val a = deck()
        feed(a, blocks = 200) // 4 s

        val b = deck()
        assertEquals(DeckRole.INCOMING, role(b))
        assertEquals(DeckRole.OUTGOING, role(a))
    }

    @Test
    fun progressAdvancesWithAudioFedAndCompletes() {
        val a = deck()
        feed(a, blocks = 200)
        val b = deck()
        assertEquals(DeckRole.INCOMING, role(b))

        // Halfway through the 5 s transition: 2.5 s of audio.
        val mid = feed(b, blocks = 125)
        assertEquals(DeckRole.INCOMING, mid.role)
        assertEquals(0.5f, mid.progressEnd, 0.01f)

        // The rest of the transition plus one block: it ends and both decks go back to solo.
        feed(b, blocks = 126)
        assertEquals(DeckRole.SOLO, role(b))
        assertEquals(DeckRole.SOLO, role(a))

        val stats = engine.stats()
        assertEquals(1, stats.transitionsMixed)
        assertEquals(5_000.0, stats.lastTransitionMs.toDouble(), 40.0)
        assertFalse(stats.mixing)
    }

    @Test
    fun pausedPlaybackDoesNotAdvanceTheTransition() {
        val a = deck()
        feed(a, blocks = 200)
        val b = deck()
        role(b)
        feed(b, blocks = 50) // 1 s in

        // Nothing is fed for a while (a pause). Playback time stands still, so the transition survives
        // and one more block moves it by exactly one block, not by the time that passed.
        val plan = feed(b, blocks = 1)
        assertEquals(DeckRole.INCOMING, plan.role)
        // 52 blocks of 20 ms = 1.04 s into a 5 s transition.
        assertEquals(1.04f / 5f, plan.progressEnd, 0.01f)
    }

    @Test
    fun outgoingGoingQuietEndsTheTransition() {
        // A short "gone" window, so the scenario fits inside the transition instead of outlasting it.
        engine = DjEngine(snapshot = { DjSnapshot(active, DjProfiles.forId(ProfileId.WIRED), transitionMs) }, staleMs = 40_000, outgoingGoneMs = 1_000)
        val a = deck()
        feed(a, blocks = 200)
        val b = deck()
        role(b)

        // The incoming deck keeps playing past the window while the outgoing one is not fed at all.
        feed(b, blocks = 60) // 1.2 s, still inside the 5 s transition
        assertEquals(DeckRole.SOLO, role(b))
        assertEquals(0, engine.stats().transitionsMixed) // it ended early, so it does not count
    }

    @Test
    fun deckEndingEndsTheTransition() {
        val a = deck()
        feed(a, blocks = 200)
        val b = deck()
        role(b)
        a.onEndOfStream()
        assertEquals(DeckRole.SOLO, role(b))
        assertFalse(engine.stats().mixing)
    }

    @Test
    fun inactiveEngineNeverMixes() {
        active = false
        val a = deck()
        feed(a, blocks = 200)
        val b = deck()
        assertEquals(DeckRole.SOLO, role(b))
        assertEquals(DeckRole.SOLO, role(a))
    }

    @Test
    fun deactivatingMidTransitionStopsIt() {
        val a = deck()
        feed(a, blocks = 200)
        val b = deck()
        role(b)
        active = false
        assertEquals(DeckRole.SOLO, role(b))
        assertEquals(DeckRole.SOLO, role(a))
    }

    @Test
    fun aDeckThatHasAlreadyPlayedIsNotIncoming() {
        val a = deck()
        feed(a, blocks = 600) // 12 s, past the incoming window
        val b = deck()
        feed(b, blocks = 600) // b too: a deck only counts as incoming while it is young
        assertEquals(DeckRole.SOLO, role(b))
        assertEquals(DeckRole.SOLO, role(a))
    }

    @Test
    fun aDeckThatBarelyStartedIsNotOutgoing() {
        val a = deck()
        feed(a, blocks = 50) // 1 s, below the outgoing minimum
        val b = deck()
        assertEquals(DeckRole.SOLO, role(b))
        assertEquals(DeckRole.SOLO, role(a))
    }

    @Test
    fun aStaleDeckIsNotOutgoing() {
        val a = deck()
        feed(a, blocks = 200)
        // a stopped; enough playback time passes without it that it no longer counts as playing.
        feed(deck(), blocks = 10)
        val b = deck()
        assertEquals(DeckRole.SOLO, role(b))
    }

    @Test
    fun aDeckCanBeIncomingAndOutgoingOnlyOnce() {
        val a = deck()
        feed(a, blocks = 200)
        val b = deck()
        feed(b, blocks = 251) // completes the transition

        val c = deck()
        // a is still the outgoing deck of the finished transition, so c pairs with b.
        assertEquals(DeckRole.INCOMING, role(c))
        // b was the incoming deck and is now the outgoing one: each role exactly once.
        assertEquals(DeckRole.OUTGOING, role(b))

        val d = deck()
        // c has been incoming once already, and b has been both, so neither can pair with d.
        assertEquals(DeckRole.SOLO, role(d))
    }

    @Test
    fun aFlushCancelsAnInvolvedDeck() {
        val a = deck()
        feed(a, blocks = 200)
        val b = deck()
        role(b)
        a.onReset()
        assertEquals(DeckRole.SOLO, role(b))
    }

    @Test
    fun transitionLengthFollowsTheSnapshotAndIsClamped() {
        transitionMs = 2_000
        val a = deck()
        feed(a, blocks = 200)
        val b = deck()
        role(b)
        feed(b, blocks = 99) // 1.98 s of a 2 s transition
        assertTrue(engine.stats().mixing)
        feed(b, blocks = 2)
        assertFalse(engine.stats().mixing)
        assertEquals(2_000.0, engine.stats().lastTransitionMs.toDouble(), 40.0)
    }

    @Test
    fun absurdDurationIsClamped() {
        transitionMs = 1_000_000
        val a = deck()
        feed(a, blocks = 200)
        val b = deck()
        role(b)
        // After 29 s the transition is still running (clamped to 30 s), not finished at once.
        feed(b, blocks = 1_450)
        assertTrue(engine.stats().mixing)
    }

    @Test
    fun twoDecksFeedingConcurrentlyStayConsistent() {
        val a = deck()
        feed(a, blocks = 200)
        val b = deck()
        role(b)

        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads =
            listOf(a, b).map { deck ->
                Thread {
                    try {
                        repeat(200) { deck.beginBlock(BLOCK_FRAMES, RATE) }
                    } catch (t: Throwable) {
                        errors += t
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue("concurrent feeding threw: $errors", errors.isEmpty())
    }

    @Test
    fun finishedDecksArePruned() {
        repeat(8) {
            val d = deck()
            feed(d, blocks = 1)
            d.onEndOfStream()
        }
        val fresh = deck()
        feed(fresh, blocks = 1)
        assertTrue(engine.stats().decksAlive <= 6)
    }

    private companion object {
        const val RATE = 48_000
        const val BLOCK_FRAMES = 960
    }
}
