package io.github.einekratzekatze.maps.data.db

import androidx.room.TypeConverter
import io.github.einekratzekatze.maps.navigation.TravelMode as NavTravelMode

enum class ListType { NORMAL, EXTERNAL }

enum class ImportantKind { HOME, WORK, CUSTOM }

/** Stored travel mode. Distinct from [NavTravelMode] (navigation code); see the mapping functions below. */
enum class TravelMode { DRIVE, WALK, BIKE, TRANSIT, UNKNOWN }

fun NavTravelMode?.toDb(): TravelMode = when (this) {
    NavTravelMode.Drive -> TravelMode.DRIVE
    NavTravelMode.Bike -> TravelMode.BIKE
    NavTravelMode.Walk -> TravelMode.WALK
    NavTravelMode.Transit -> TravelMode.TRANSIT
    null -> TravelMode.UNKNOWN
}

fun TravelMode.toNavigation(): NavTravelMode? = when (this) {
    TravelMode.DRIVE -> NavTravelMode.Drive
    TravelMode.BIKE -> NavTravelMode.Bike
    TravelMode.WALK -> NavTravelMode.Walk
    TravelMode.TRANSIT -> NavTravelMode.Transit
    TravelMode.UNKNOWN -> null
}

/** Enums are stored by `name`, never by ordinal (schema.md section 0). */
class EnumConverters {
    @TypeConverter fun listTypeToString(v: ListType): String = v.name
    /** No fallback value exists: an unknown name throws [IllegalArgumentException]. */
    @TypeConverter fun stringToListType(s: String): ListType = ListType.valueOf(s)

    @TypeConverter fun importantKindToString(v: ImportantKind): String = v.name
    /** No fallback value exists: an unknown name throws [IllegalArgumentException]. */
    @TypeConverter fun stringToImportantKind(s: String): ImportantKind = ImportantKind.valueOf(s)

    @TypeConverter fun travelModeToString(v: TravelMode): String = v.name
    /** Unknown names map to [TravelMode.UNKNOWN]. */
    @TypeConverter fun stringToTravelMode(s: String): TravelMode =
        TravelMode.entries.firstOrNull { it.name == s } ?: TravelMode.UNKNOWN
}
