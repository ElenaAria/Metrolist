package ultra.core.model

public data class ArtistRef(
    public val id: ArtistId?,
    public val name: String,
)

public data class AlbumRef(
    public val id: AlbumId?,
    public val title: String,
)

/** A source-independent description of a playable track. Adapters in :app map upstream entities to it. */
public data class Track(
    public val id: TrackId,
    public val title: String,
    public val artists: List<ArtistRef> = emptyList(),
    public val album: AlbumRef? = null,
    public val durationMs: Long? = null,
    public val artworkUrl: String? = null,
) {
    init {
        require(durationMs == null || durationMs >= 0) { "durationMs must not be negative: $durationMs" }
    }

    public val artistNames: String
        get() = artists.joinToString(", ") { it.name }
}
