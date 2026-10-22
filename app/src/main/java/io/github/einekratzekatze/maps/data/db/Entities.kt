package io.github.einekratzekatze.maps.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** The only definition of the schema is docs/design/schema.md. */

@Entity(tableName = "PlaceList", indices = [Index("sortOrder")])
data class PlaceList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    val type: ListType,
    val createdAt: Long,
)

@Entity(
    tableName = "ExternalList",
    foreignKeys = [ForeignKey(PlaceList::class, ["id"], ["listId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("listId", unique = true), Index("mapId", unique = true)],
)
data class ExternalList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val mapId: String,
    val sourceUrl: String,
    val lastFetchedAt: Long? = null,
    val rawKml: String? = null,
)

@Entity(
    tableName = "ExternalSublist",
    foreignKeys = [ForeignKey(ExternalList::class, ["id"], ["externalListId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("externalListId")],
)
data class ExternalSublist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val externalListId: Long,
    val name: String,
    @ColumnInfo(defaultValue = "1") val visible: Boolean = true,
    val sortOrder: Int,
)

@Entity(
    tableName = "Place",
    foreignKeys = [
        ForeignKey(PlaceList::class, ["id"], ["listId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ExternalSublist::class, ["id"], ["externalSublistId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("listId"),
        Index("externalSublistId"),
        Index("lat", "lon"),
        Index("nameNormalized"),
    ],
)
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val externalSublistId: Long? = null,
    val name: String,
    val nameNormalized: String,
    val lat: Double,
    val lon: Double,
    val address: String? = null,
    val addressNormalized: String? = null,
    val notes: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "ImportantLocation", indices = [Index("kind"), Index("sortOrder")])
data class ImportantLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: ImportantKind,
    val label: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val address: String? = null,
    val sortOrder: Int,
    val updatedAt: Long,
)

@Entity(
    tableName = "Drive",
    foreignKeys = [ForeignKey(PlaceList::class, ["id"], ["listId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("listId")],
)
data class Drive(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val originLabel: String? = null,
    val originLat: Double? = null,
    val originLon: Double? = null,
    val destLabel: String? = null,
    val destLat: Double,
    val destLon: Double,
    @ColumnInfo(defaultValue = "'[]'") val waypointsJson: String = "[]",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val travelMode: TravelMode = TravelMode.UNKNOWN,
    val createdAt: Long,
)

@Entity(
    tableName = "RecentSearch",
    indices = [Index("queryNormalized", unique = true), Index("timestamp")],
)
data class RecentSearch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    val queryNormalized: String,
    val timestamp: Long,
)

data class NearbyPlaceRow(
    val id: Long, val name: String, val nameNormalized: String,
    val lat: Double, val lon: Double,
    val listId: Long, val listName: String, val listColor: Int,
)

data class PlaceDetailRow(
    val id: Long, val name: String, val lat: Double, val lon: Double,
    val address: String?, val notes: String?,
    val listId: Long, val listName: String, val listColor: Int, val listType: ListType,
)

data class ListWithCount(@Embedded val list: PlaceList, val itemCount: Int)
