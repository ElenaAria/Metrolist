package ultra.core.network

public enum class InstanceKind { Piped, Invidious, Homelab }

/**
 * One backend that can answer API calls: a public Piped or Invidious instance, or the user's own
 * homelab server. Instances are plain data; their runtime health lives in the
 * [ultra.core.resilience.HealthRegistry] under [id].
 */
public data class ApiInstance(
    /** Stable, unique key. Health statistics are tracked under it. */
    public val id: String,
    public val kind: InstanceKind,
    /** Absolute http(s) URL without a trailing slash, e.g. `https://pipedapi.example.org`. */
    public val baseUrl: String,
    /** Higher wins. Ties are broken by health, then latency. */
    public val priority: Int = 0,
    public val enabled: Boolean = true,
    /** Human-readable name for settings screens. */
    public val label: String? = null,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) { "baseUrl must be http(s): $baseUrl" }
        require(baseUrl.substringAfter("://").isNotBlank()) { "baseUrl has no host: $baseUrl" }
        require(!baseUrl.endsWith('/')) { "baseUrl must not end with '/': $baseUrl" }
    }

    /** Joins [path] onto [baseUrl] with exactly one slash between them. */
    public fun url(path: String): String = baseUrl + "/" + path.trimStart('/')

    public companion object {
        /** Builds an instance from a user-typed URL: trims whitespace and trailing slashes, derives the id. */
        public fun create(
            kind: InstanceKind,
            baseUrl: String,
            priority: Int = 0,
            enabled: Boolean = true,
            label: String? = null,
        ): ApiInstance {
            val normalized = baseUrl.trim().trimEnd('/')
            return ApiInstance(
                id = "${kind.name.lowercase()}:$normalized",
                kind = kind,
                baseUrl = normalized,
                priority = priority,
                enabled = enabled,
                label = label,
            )
        }
    }
}
