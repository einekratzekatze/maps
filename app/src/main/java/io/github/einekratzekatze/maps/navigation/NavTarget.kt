package io.github.einekratzekatze.maps.navigation

import io.github.einekratzekatze.maps.domain.LatLon

data class NavTarget(val latLon: LatLon?, val label: String?) {
    init {
        require(latLon != null || label != null) { "NavTarget needs a latLon or a label" }
    }
}
