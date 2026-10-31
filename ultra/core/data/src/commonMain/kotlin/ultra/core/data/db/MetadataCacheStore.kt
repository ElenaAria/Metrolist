package ultra.core.data.db

import kotlin.time.Clock
import kotlin.time.Duration

/**
 * A key/value cache with a per-entry time to live, on top of [MetadataCacheDao]. Expired entries are
 * never returned, even before [purgeExpired] physically removes them.
 *
 * @param nowEpochMs the clock, in epoch milliseconds. Injected so tests control time.
 */
public class MetadataCacheStore(
    private val dao: MetadataCacheDao,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** The cached value, or null when there is none or it has expired. */
    public suspend fun get(key: String): String? {
        val entry = dao.find(key) ?: return null
        return if (entry.expiresAtEpochMs > nowEpochMs()) entry.value else null
    }

    /** Stores [value] under [key] for [ttl], replacing any previous entry. */
    public suspend fun put(
        key: String,
        value: String,
        ttl: Duration,
    ) {
        require(ttl.isPositive() && ttl.isFinite()) { "ttl must be positive and finite: $ttl" }
        val now = nowEpochMs()
        dao.upsert(MetadataCacheEntity(key, value, fetchedAtEpochMs = now, expiresAtEpochMs = now + ttl.inWholeMilliseconds))
    }

    /** The cached value if fresh, otherwise [produce]'s result, which is cached for [ttl] before returning. */
    public suspend fun getOrPut(
        key: String,
        ttl: Duration,
        produce: suspend () -> String,
    ): String = get(key) ?: produce().also { put(key, it, ttl) }

    public suspend fun remove(key: String) {
        dao.delete(key)
    }

    /** Physically deletes expired entries; returns how many. Call occasionally, for example at startup. */
    public suspend fun purgeExpired(): Int = dao.deleteExpired(nowEpochMs())

    public suspend fun clear() {
        dao.clear()
    }

    /** Rows currently stored, including expired ones not purged yet. */
    public suspend fun size(): Int = dao.count()
}
