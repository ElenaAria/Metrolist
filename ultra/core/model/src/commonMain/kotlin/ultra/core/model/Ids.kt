package ultra.core.model

import kotlin.jvm.JvmInline

/**
 * Identifies a track across every source.
 *
 * Upstream treats every media id as a YouTube video id, so YouTube tracks keep their raw id and
 * carry no namespace. Tracks from other sources (Subsonic, Jellyfin, a homelab server, ...) are
 * namespaced as `<namespace>:<id>`, which keeps them from ever colliding with a video id and lets
 * the S7 stream-resolution seam recognise which resolver owns a track.
 */
@JvmInline
public value class TrackId(public val value: String) {
    init {
        require(value.isNotBlank()) { "TrackId must not be blank" }
    }

    /** The source namespace, or null for a plain YouTube video id. */
    public val namespace: String?
        get() = value.substringBefore(NAMESPACE_SEPARATOR, missingDelimiterValue = "").ifEmpty { null }

    /** The id within its source, without the namespace. */
    public val rawId: String
        get() = value.substringAfter(NAMESPACE_SEPARATOR, missingDelimiterValue = value)

    public val isYouTube: Boolean
        get() = namespace == null

    override fun toString(): String = value

    public companion object {
        private const val NAMESPACE_SEPARATOR = ':'

        public fun youtube(videoId: String): TrackId = TrackId(videoId)

        public fun namespaced(
            namespace: String,
            id: String,
        ): TrackId {
            require(namespace.isNotBlank() && NAMESPACE_SEPARATOR !in namespace) {
                "namespace must be non-blank and must not contain '$NAMESPACE_SEPARATOR': '$namespace'"
            }
            require(id.isNotBlank()) { "id must not be blank" }
            return TrackId("$namespace$NAMESPACE_SEPARATOR$id")
        }
    }
}

@JvmInline
public value class ArtistId(public val value: String)

@JvmInline
public value class AlbumId(public val value: String)

@JvmInline
public value class PlaylistId(public val value: String)
