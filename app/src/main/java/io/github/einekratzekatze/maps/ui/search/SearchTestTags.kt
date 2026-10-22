package io.github.einekratzekatze.maps.ui.search

object SearchTestTags {
    const val Field = "search_field"
    const val Clear = "search_clear"
    const val Menu = "search_menu"
    const val DirectionsBack = "directions_back"
    const val Panel = "suggestion_panel"
    const val GoogleError = "google_error"
    fun saved(id: Long) = "suggestion_saved_$id"
    fun recent(id: Long) = "suggestion_recent_$id"
    fun recentDelete(id: Long) = "suggestion_recent_delete_$id"
    fun prediction(placeId: String) = "suggestion_prediction_$placeId"
}
