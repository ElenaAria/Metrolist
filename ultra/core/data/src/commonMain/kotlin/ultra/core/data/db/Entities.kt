package ultra.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Result of analysing one track's audio. Keyed by the track id as a plain string with no foreign key
 * into upstream's database: tracks can come from any source (YouTube, a homelab, local files), and
 * ultra must not depend on upstream's schema.
 */
@Entity(tableName = "track_analysis")
public data class TrackAnalysisEntity(
    @PrimaryKey
    @ColumnInfo(name = "track_id")
    public val trackId: String,
    /** BS.1770 integrated loudness; null when the track was too quiet or too short to measure. */
    @ColumnInfo(name = "integrated_lufs")
    public val integratedLufs: Double?,
    /** Largest absolute sample, linear (1.0 is full scale). */
    @ColumnInfo(name = "sample_peak")
    public val samplePeak: Double?,
    /** Bumped whenever the analyser changes, so stale results can be found and recomputed. */
    @ColumnInfo(name = "analyzer_version")
    public val analyzerVersion: Int,
    @ColumnInfo(name = "analyzed_at")
    public val analyzedAtEpochMs: Long,
)

/** One cached response (lyrics, instance metadata, ...) with an absolute expiry. */
@Entity(tableName = "metadata_cache")
public data class MetadataCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "cache_key")
    public val cacheKey: String,
    @ColumnInfo(name = "value")
    public val value: String,
    @ColumnInfo(name = "fetched_at")
    public val fetchedAtEpochMs: Long,
    @ColumnInfo(name = "expires_at")
    public val expiresAtEpochMs: Long,
)
