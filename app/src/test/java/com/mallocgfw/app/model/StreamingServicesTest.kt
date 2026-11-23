package com.mallocgfw.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingServicesTest {
    private val services = StreamingMediaManager.services

    @Test
    fun serviceIdsAreUnique() {
        assertEquals(services.size, services.map { it.id }.toSet().size)
    }

    @Test
    fun existingServiceIdsAreKept() {
        // Saved selections refer to these ids.
        val ids = services.map { it.id }.toSet()
        listOf(
            "netflix", "disney", "youtube", "youtube_music", "primevideo", "amazon_prime_video",
            "hulu", "hulu_jp", "hulu_usa", "max", "hbo", "hbo_asia", "hbo_hk", "spotify",
            "apple_tv", "apple_music", "bahamut", "abematv", "bilibili", "bilibili_intl",
            "viutv", "line_tv", "litv", "kktv", "kkbox", "discovery_plus", "fox_now",
            "encore_tvb", "dazn", "tidal", "soundcloud", "pandora", "deezer",
            "paramount_plus", "peacock", "niconico", "hami_video", "tvb",
        ).forEach { assertTrue("missing $it", it in ids) }
    }

    @Test
    fun everyServiceHasACountryTag() {
        services.forEach { assertTrue("${it.id} has no region", it.regions.isNotEmpty()) }
    }

    @Test
    fun everyCountryTabHasServices() {
        StreamingRegion.entries.forEach { region ->
            assertTrue("$region is empty", StreamingMediaManager.services(region).isNotEmpty())
        }
        assertEquals(services.size, StreamingMediaManager.services(null).size)
    }

    @Test
    fun onlyServicesWithAChosenNodeAreRouted() {
        val settings = AppStateStore.defaultSettings().copy(
            streamingRoutingEnabled = true,
            streamingSelections = listOf(
                StreamingRouteSelection("netflix", "node_jp"),
                StreamingRouteSelection("bilibili", ""),
                StreamingRouteSelection("disney", StreamingMediaManager.DIRECT),
            ),
        )
        assertEquals(setOf("netflix", "disney"), StreamingMediaManager.routedServices(settings).map { it.id }.toSet())
    }
}
