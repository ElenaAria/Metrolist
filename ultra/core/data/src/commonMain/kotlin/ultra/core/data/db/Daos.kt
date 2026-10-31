package ultra.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
public interface TrackAnalysisDao {
    @Upsert
    public suspend fun upsert(entity: TrackAnalysisEntity)

    @Query("SELECT * FROM track_analysis WHERE track_id = :trackId")
    public suspend fun find(trackId: String): TrackAnalysisEntity?

    @Query("SELECT * FROM track_analysis WHERE track_id = :trackId")
    public fun observe(trackId: String): Flow<TrackAnalysisEntity?>

    @Query("DELETE FROM track_analysis WHERE track_id = :trackId")
    public suspend fun delete(trackId: String)

    /** Drops results produced by analyser versions older than [minVersion]; returns how many rows went. */
    @Query("DELETE FROM track_analysis WHERE analyzer_version < :minVersion")
    public suspend fun deleteOlderThanVersion(minVersion: Int): Int

    @Query("SELECT COUNT(*) FROM track_analysis")
    public suspend fun count(): Int
}

@Dao
public interface MetadataCacheDao {
    @Upsert
    public suspend fun upsert(entity: MetadataCacheEntity)

    @Query("SELECT * FROM metadata_cache WHERE cache_key = :key")
    public suspend fun find(key: String): MetadataCacheEntity?

    @Query("DELETE FROM metadata_cache WHERE cache_key = :key")
    public suspend fun delete(key: String)

    /** Removes everything that expired at or before [nowEpochMs]; returns how many rows went. */
    @Query("DELETE FROM metadata_cache WHERE expires_at <= :nowEpochMs")
    public suspend fun deleteExpired(nowEpochMs: Long): Int

    @Query("DELETE FROM metadata_cache")
    public suspend fun clear()

    @Query("SELECT COUNT(*) FROM metadata_cache")
    public suspend fun count(): Int
}
