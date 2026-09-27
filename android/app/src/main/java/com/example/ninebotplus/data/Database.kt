package com.example.ninebotplus.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TrackPointPayload(
    val id: String,
    val date: Long,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val accelerationG: Double,
    val horizontalAccuracy: Double? = null,
)

@Entity(tableName = "recorded_rides")
data class RecordedRideEntity(
    @PrimaryKey val id: String,
    val vehicleSn: String?,
    val associatedRideId: String?,
    val startedAt: Long,
    val endedAt: Long,
    val distanceMeters: Double,
    val maxSpeedKmh: Double,
    val averageSpeedKmh: Double,
    val maxAccelerationG: Double,
    val pointsJson: String,
)

@Entity(tableName = "interface_rides")
data class InterfaceRideEntity(
    @PrimaryKey val identityKey: String,
    val vehicleSn: String,
    val recordId: String,
    val startedAt: Long?,
    val endedAt: Long?,
    val mileage: Double?,
    val energy: Double?,
    val usedElectricity: Double?,
    val durationMinutes: Double?,
    val speed: Double?,
    val rawJson: String?,
)

@Entity(tableName = "history_points")
data class HistoryPointEntity(
    @PrimaryKey val id: String,
    val vehicleSn: String,
    val date: Long,
    val battery: Int?,
    val endurance: Double?,
    val totalMileage: Double?,
    val isCharging: Boolean?,
    val isLocked: Boolean?,
    val isPoweredOn: Boolean?,
)

@Entity(tableName = "ride_details")
data class RideDetailEntity(
    @PrimaryKey val key: String,
    val vehicleSn: String,
    val rideId: String,
    val fetchedAt: Long,
    val rawJson: String,
)

class Converters {
    @TypeConverter
    fun fromBool(value: Boolean?): Int? = value?.let { if (it) 1 else 0 }

    @TypeConverter
    fun toBool(value: Int?): Boolean? = value?.let { it != 0 }
}

@Dao
interface RideDao {
    @Query("SELECT * FROM recorded_rides ORDER BY startedAt DESC")
    fun observeRecordedRides(): Flow<List<RecordedRideEntity>>

    @Query("SELECT * FROM recorded_rides ORDER BY startedAt DESC")
    suspend fun recordedRides(): List<RecordedRideEntity>

    @Query("SELECT * FROM recorded_rides WHERE id = :id LIMIT 1")
    suspend fun recordedRide(id: String): RecordedRideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecordedRide(ride: RecordedRideEntity)

    @Query("DELETE FROM recorded_rides WHERE id = :id")
    suspend fun deleteRecordedRide(id: String)

    @Query("SELECT COUNT(*) FROM recorded_rides")
    suspend fun recordedRideCount(): Int

    @Query("SELECT * FROM interface_rides WHERE vehicleSn = :sn ORDER BY startedAt DESC")
    suspend fun interfaceRides(sn: String): List<InterfaceRideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInterfaceRides(rides: List<InterfaceRideEntity>)

    @Query("SELECT COUNT(*) FROM interface_rides WHERE vehicleSn = :sn")
    suspend fun interfaceRideCount(sn: String): Int

    @Query("SELECT * FROM history_points WHERE vehicleSn = :sn ORDER BY date ASC")
    suspend fun historyPoints(sn: String): List<HistoryPointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryPoints(points: List<HistoryPointEntity>)

    @Query("SELECT COUNT(*) FROM history_points WHERE vehicleSn = :sn")
    suspend fun historyCount(sn: String): Int

    @Query("SELECT * FROM ride_details WHERE `key` = :key LIMIT 1")
    suspend fun rideDetail(key: String): RideDetailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRideDetail(detail: RideDetailEntity)

    @Query("SELECT COUNT(*) FROM ride_details")
    suspend fun rideDetailCount(): Int
}

@Database(
    entities = [
        RecordedRideEntity::class,
        InterfaceRideEntity::class,
        HistoryPointEntity::class,
        RideDetailEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NinePlusDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao

    companion object {
        @Volatile
        private var instance: NinePlusDatabase? = null

        fun get(context: Context): NinePlusDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NinePlusDatabase::class.java,
                    "nineplus.db",
                )
                    // NEVER wipe user ride history on a schema bump. Migrations
                    // must be added explicitly when version increases.
                    .build()
                    .also { instance = it }
            }
        }
    }
}

object TrackPointCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(points: List<TrackPointPayload>): String = json.encodeToString(points)

    fun decode(raw: String): List<TrackPointPayload> =
        runCatching { json.decodeFromString<List<TrackPointPayload>>(raw) }.getOrDefault(emptyList())
}
