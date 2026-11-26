package org.dagr.navigation.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "collections")
data class WaypointCollection(
    @PrimaryKey val id: String,
    val name: String
)

@Entity(
    tableName = "waypoints",
    foreignKeys = [
        ForeignKey(
            entity = WaypointCollection::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("collectionId")]
)
data class Waypoint(
    @PrimaryKey val id: String,
    val collectionId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double
)
