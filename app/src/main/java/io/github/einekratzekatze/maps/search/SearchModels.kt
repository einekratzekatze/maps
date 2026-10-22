package io.github.einekratzekatze.maps.search

/** One row in the suggestion panel (custom-search-field.md §3). */
sealed interface Suggestion {
    data class SavedPlace(val id: Long, val name: String, val address: String?, val lat: Double, val lon: Double, val listColor: Int) : Suggestion
    data class Recent(val id: Long, val query: String) : Suggestion
    data class Prediction(val placeId: String, val primary: String, val secondary: String?) : Suggestion
}

enum class SectionKind { Saved, Recent, Google }

data class SuggestionSection(val kind: SectionKind, val items: List<Suggestion>)
