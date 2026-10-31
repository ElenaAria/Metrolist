package ultra.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public enum class PlaybackStatus {
    /** Nothing loaded. */
    Idle,

    /** Loaded but not enough data to play yet. */
    Buffering,

    /** Enough data to play; see [PlaybackState.isPlaying] for whether it is. */
    Ready,

    /** The queue finished. */
    Ended,
}

public enum class RepeatMode { Off, One, All }

public data class PlaybackState(
    public val status: PlaybackStatus = PlaybackStatus.Idle,
    public val isPlaying: Boolean = false,
    public val currentTrack: Track? = null,
    public val positionMs: Long = 0L,
    public val bufferedPositionMs: Long = 0L,
    public val durationMs: Long? = null,
    public val speed: Float = 1f,
    public val repeatMode: RepeatMode = RepeatMode.Off,
    public val shuffleEnabled: Boolean = false,
)

/** The queue and which entry is current, published together so observers never see them out of sync. */
public data class PlaybackQueue(
    public val tracks: List<Track> = emptyList(),
    /** Index into [tracks], or -1 when nothing is current. */
    public val currentIndex: Int = -1,
) {
    init {
        require(currentIndex == -1 || currentIndex in tracks.indices) {
            "currentIndex $currentIndex is outside the queue of ${tracks.size}"
        }
    }
}

public enum class TrackChangeReason { Auto, UserSkip, QueueChanged, Repeat }

public sealed interface PlaybackEvent {
    public data class TrackChanged(
        public val track: Track?,
        public val reason: TrackChangeReason,
    ) : PlaybackEvent

    public data class Failed(
        public val track: Track?,
        public val cause: Throwable,
    ) : PlaybackEvent
}

/**
 * What ultra features need from a player. On Android an adapter in :app implements it over
 * upstream's MusicService/MediaController; on desktop a separate engine does (P10). Features
 * depend on this port, never on Media3.
 *
 * Commands are fire-and-forget (they are asynchronous on every real player); observe [state],
 * [queue] and [events] for their effect.
 */
public interface PlaybackEnginePort {
    public val state: StateFlow<PlaybackState>
    public val queue: StateFlow<PlaybackQueue>

    /** One-shot notifications that are not part of [state]. */
    public val events: Flow<PlaybackEvent>

    public fun play()

    public fun pause()

    public fun stop()

    public fun seekTo(positionMs: Long)

    public fun skipToNext()

    public fun skipToPrevious()

    public fun skipToIndex(index: Int)

    public fun setQueue(
        tracks: List<Track>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
    )

    /** Appends [tracks], or inserts them at [atIndex] when given. */
    public fun enqueue(
        tracks: List<Track>,
        atIndex: Int? = null,
    )

    public fun removeFromQueue(index: Int)

    public fun setRepeatMode(mode: RepeatMode)

    public fun setShuffleEnabled(enabled: Boolean)

    public fun setSpeed(speed: Float)
}
