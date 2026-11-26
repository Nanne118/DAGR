package org.dagr.navigation.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface NavigationDao {
    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE")
    fun observeCollections(): Flow<List<WaypointCollection>>

    @Query("SELECT * FROM waypoints WHERE collectionId = :collectionId ORDER BY name COLLATE NOCASE")
    fun observeWaypoints(collectionId: String): Flow<List<Waypoint>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCollection(collection: WaypointCollection)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWaypoint(waypoint: Waypoint)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWaypoints(waypoints: List<Waypoint>)

    @Query("DELETE FROM waypoints WHERE id = :waypointId")
    suspend fun deleteWaypoint(waypointId: String)
}

@Database(
    entities = [WaypointCollection::class, Waypoint::class],
    version = 1,
    exportSchema = false
)
abstract class NavigationDatabase : RoomDatabase() {
    abstract fun navigationDao(): NavigationDao

    companion object {
        @Volatile
        private var instance: NavigationDatabase? = null

        fun get(context: Context): NavigationDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NavigationDatabase::class.java,
                    "dagr.db"
                ).build().also { instance = it }
            }
    }
}
