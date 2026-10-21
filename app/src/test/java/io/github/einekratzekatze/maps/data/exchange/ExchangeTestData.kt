package io.github.einekratzekatze.maps.data.exchange

import io.github.einekratzekatze.maps.domain.LatLon

internal object ExchangeTestData {
    val nastyName = "Café \"Zwölf\" & <Söhne> 'Ärger' 🚗 ß"

    val sample = listOf(
        ExchangeList(
            name = "Berlin & Umgebung 🐻",
            places = listOf(
                ExchangePlace("Brandenburger Tor", LatLon(52.5163, 13.3777), "Pariser Platz 1", "Notiz <b>fett</b> & mehr"),
                ExchangePlace(nastyName, LatLon(-33.8688, 151.2093)),
                ExchangePlace("West of Greenwich", LatLon(51.5074, -0.1278), null, "line1\nline2"),
            ),
            drives = listOf(
                ExchangeDrive(
                    name = "Home → Work",
                    origin = LatLon(52.5, 13.4), originLabel = "Home",
                    destination = LatLon(52.6, 13.5), destinationLabel = "Büro & Co",
                    waypoints = listOf(
                        ExchangeWaypoint("Stop <1>", LatLon(52.55, 13.45)),
                        ExchangeWaypoint(null, LatLon(52.56, 13.46)),
                        ExchangeWaypoint("", LatLon(52.57, 13.47)).copy(label = null),
                    ),
                    travelMode = "BIKE",
                ),
                ExchangeDrive(
                    name = "From current location",
                    origin = null,
                    destination = LatLon(48.1374, 11.5755),
                    waypoints = listOf(ExchangeWaypoint("Via", LatLon(49.0, 11.0))),
                    travelMode = "DRIVE",
                ),
                ExchangeDrive("Direct, no origin", null, destination = LatLon(0.0, 0.0)),
            ),
        ),
        ExchangeList("Empty list"),
        ExchangeList("Only places", places = listOf(ExchangePlace("P", LatLon(1.0, 2.0)))),
    )

    /** Lists as plain Lists for equality. */
    fun ExchangeList.materialized() = copy(places = places.toList(), drives = drives.toList())
    fun List<ExchangeList>.materialized() = map { it.materialized() }
}
