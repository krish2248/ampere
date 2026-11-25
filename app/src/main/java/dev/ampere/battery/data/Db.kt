package dev.ampere.battery.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "samples", indices = [Index("ts"), Index("sessionId")])
data class Sample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val level: Int,
    val mv: Int?,
    /** µA, positive = into the battery. */
    val ua: Int?,
    val cc: Int?,
    val tempT: Int,
    val charging: Boolean,
    val plug: Int,
    val thermal: Int,
    val screenOn: Boolean,
    val sessionId: Long,
) {
    val mw: Int? get() = if (mv == null || ua == null) null else (mv.toLong() * ua / 1_000_000L).toInt()
}

const val KIND_CHARGE = 0
const val KIND_DRAIN = 1

@Entity(tableName = "sessions", indices = [Index("startTs"), Index("kind")])
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: Int,
    val startTs: Long,
    val endTs: Long,
    val startLevel: Int,
    val endLevel: Int,
    val startCc: Int? = null,
    val endCc: Int? = null,
    val plug: Int = 0,
    val peakMw: Int = 0,
    val avgMw: Int = 0,
    val peakTempT: Int = 0,
    val avgTempT: Int = 0,
    val minTempT: Int = 0,
    val minMv: Int = 0,
    val maxMv: Int = 0,
    val energyMwh: Float = 0f,
    val chargeUah: Float = 0f,
    val durationMs: Long = 0,
    /** Time actually covered by back-to-back samples; the integrals above only cover this. */
    val coveredMs: Long = 0,
    val screenOnMs: Long = 0,
    val uahOn: Float = 0f,
    val uahOff: Float = 0f,
    val heat40Ms: Long = 0,
    val heat43Ms: Long = 0,
    val holdMs: Long = 0,
    val thermalMax: Int = 0,
    /** "level:secondsFromStart" pairs, first time each percent was reached. */
    val marks: String = "",
    val score: Int = -1,
    val reasons: String = "",
    val negotiatedMw: Int = 0,
    val unstable: Boolean = false,
    val bootCount: Int = 0,
    @ColumnInfo(name = "open") val open: Boolean = true,
) {
    val gained: Int get() = endLevel - startLevel
    val isCharge: Boolean get() = kind == KIND_CHARGE
}

@Entity(tableName = "estimates", indices = [Index("ts")])
data class Estimate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val uah: Int,
    val weight: Float,
    val method: String,
    val spanPct: Int,
    val tempC: Float,
    val sessionId: Long,
)

/** Rolling per-day totals; cheap to keep forever, and what goals and analytics read. */
@Entity(tableName = "days")
data class Day(
    @PrimaryKey val day: Long,
    val chargedPct: Int = 0,
    val drainedPct: Int = 0,
    val charges: Int = 0,
    val minLevel: Int = 100,
    val maxLevel: Int = 0,
    val maxTempT: Int = Int.MIN_VALUE,
    val screenOnMs: Long = 0,
    val heat40Ms: Long = 0,
    val heat43Ms: Long = 0,
    val energyInMwh: Float = 0f,
    val energyOutMwh: Float = 0f,
    val peakMw: Int = 0,
    val trackedMs: Long = 0,
    val maxUnplug: Int = 0,
)

@Entity(tableName = "unlocks")
data class Unlock(@PrimaryKey val id: String, val ts: Long)

@Entity(tableName = "goal_wins", primaryKeys = ["day", "goal"])
data class GoalWin(val day: Long, val goal: String)

@Dao
interface AmpereDao {
    @Insert
    suspend fun addSamples(list: List<Sample>)

    @Query("SELECT * FROM samples WHERE ts >= :from AND ts <= :to ORDER BY ts")
    suspend fun samples(from: Long, to: Long): List<Sample>

    @Query("SELECT * FROM samples WHERE sessionId = :id ORDER BY ts")
    fun sessionSamples(id: Long): Flow<List<Sample>>

    @Query("SELECT COUNT(*) FROM samples")
    fun sampleCount(): Flow<Int>

    @Query("DELETE FROM samples WHERE ts < :cut AND id NOT IN (SELECT MIN(id) FROM samples WHERE ts < :cut GROUP BY ts / :bucket)")
    suspend fun thin(cut: Long, bucket: Long)

    @Query("DELETE FROM samples WHERE ts < :cut")
    suspend fun dropBefore(cut: Long)

    @Insert
    suspend fun addSession(s: Session): Long

    @Upsert
    suspend fun saveSession(s: Session)

    @Query("SELECT * FROM sessions WHERE `open` = 1 ORDER BY id DESC LIMIT 1")
    suspend fun openSession(): Session?

    @Query("SELECT * FROM sessions WHERE `open` = 0 ORDER BY startTs DESC")
    fun sessions(): Flow<List<Session>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun session(id: Long): Flow<Session?>

    @Query("SELECT * FROM sessions WHERE `open` = 0 AND kind = 0 ORDER BY startTs DESC LIMIT :n")
    suspend fun lastCharges(n: Int): List<Session>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM samples WHERE sessionId = :id")
    suspend fun deleteSessionSamples(id: Long)

    @Query("DELETE FROM estimates WHERE sessionId = :id")
    suspend fun deleteSessionEstimates(id: Long)

    @Insert
    suspend fun addEstimate(e: Estimate)

    @Query("SELECT * FROM estimates ORDER BY ts DESC")
    fun estimates(): Flow<List<Estimate>>

    @Query("SELECT * FROM days WHERE day = :d")
    suspend fun day(d: Long): Day?

    @Upsert
    suspend fun saveDay(d: Day)

    @Query("SELECT * FROM days ORDER BY day")
    fun days(): Flow<List<Day>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun unlock(u: Unlock): Long

    @Query("SELECT * FROM unlocks")
    fun unlocks(): Flow<List<Unlock>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addWins(w: List<GoalWin>)

    @Query("SELECT * FROM goal_wins ORDER BY day")
    fun wins(): Flow<List<GoalWin>>

    @Query("DELETE FROM estimates")
    suspend fun clearEstimates()
}

@Database(
    entities = [Sample::class, Session::class, Estimate::class, Day::class, Unlock::class, GoalWin::class],
    version = 1,
)
abstract class AmpereDb : RoomDatabase() {
    abstract fun dao(): AmpereDao

    companion object {
        fun open(ctx: Context): AmpereDb = Room.databaseBuilder(ctx, AmpereDb::class.java, "ampere.db").build()
    }
}
