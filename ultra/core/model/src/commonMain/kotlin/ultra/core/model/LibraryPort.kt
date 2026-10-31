package ultra.core.model

import kotlinx.coroutines.flow.Flow

public data class PlaylistSummary(
    public val id: PlaylistId,
    public val name: String,
    public val trackCount: Int,
)

/**
 * Read access to the user's library, plus the few writes ultra features need. An adapter in :app
 * implements it over upstream's database; ultra never opens upstream's Room schema directly and
 * keeps its own data (analysis results, caches) in ultra.db, keyed by [TrackId] with no foreign keys.
 */
public interface LibraryPort {
    public fun observeLikedTracks(): Flow<List<Track>>

    public fun observeRecentlyPlayed(limit: Int = 50): Flow<List<Track>>

    public fun observePlaylists(): Flow<List<PlaylistSummary>>

    public fun observePlaylistTracks(id: PlaylistId): Flow<List<Track>>

    public fun observeIsLiked(id: TrackId): Flow<Boolean>

    public suspend fun findTrack(id: TrackId): Track?

    public suspend fun search(
        query: String,
        limit: Int = 50,
    ): List<Track>

    public suspend fun setLiked(
        id: TrackId,
        liked: Boolean,
    )

    public suspend fun recordPlay(
        id: TrackId,
        playedAtEpochMs: Long,
    )
}
