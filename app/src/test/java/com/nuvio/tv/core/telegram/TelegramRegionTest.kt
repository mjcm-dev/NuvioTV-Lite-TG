// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.core.telegram

import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramRegionTest {

    @Test
    fun appOverrideWinsOverSystem() {
        assertEquals(
            SpanishRegion.SPAIN,
            resolveSpanishRegion("es-ES", "es", "MX", "America/Mexico_City")
        )
        assertEquals(
            SpanishRegion.LATAM,
            resolveSpanishRegion("es-MX", "es", "ES", "Europe/Madrid")
        )
    }

    @Test
    fun systemCountryDecidesBucket() {
        assertEquals(
            SpanishRegion.SPAIN,
            resolveSpanishRegion(null, "es", "ES", "Europe/Madrid")
        )
        assertEquals(
            SpanishRegion.LATAM,
            resolveSpanishRegion(null, "es", "AR", "America/Argentina/Buenos_Aires")
        )
        assertEquals(
            SpanishRegion.LATAM,
            resolveSpanishRegion(null, "es", "US", "America/New_York")
        )
    }

    @Test
    fun bareSpanishFallsBackToTimezone() {
        assertEquals(
            SpanishRegion.SPAIN,
            resolveSpanishRegion(null, "es", "", "Europe/Madrid")
        )
        assertEquals(
            SpanishRegion.SPAIN,
            resolveSpanishRegion(null, "es", "", "Atlantic/Canary")
        )
        assertEquals(
            SpanishRegion.LATAM,
            resolveSpanishRegion(null, "es", "", "America/Bogota")
        )
        assertEquals(
            SpanishRegion.OTHER,
            resolveSpanishRegion(null, "es", "", "Asia/Tokyo")
        )
    }

    @Test
    fun nonSpanishIsOther() {
        assertEquals(
            SpanishRegion.OTHER,
            resolveSpanishRegion(null, "en", "US", "America/New_York")
        )
        assertEquals(
            SpanishRegion.OTHER,
            resolveSpanishRegion("en-US", "es", "ES", "Europe/Madrid")
        )
    }

    @Test
    fun tmdbTagsAndCountryPriority() {
        assertEquals("es-ES", SpanishRegion.SPAIN.tmdbLanguageTag())
        assertEquals("es-MX", SpanishRegion.LATAM.tmdbLanguageTag())
        assertEquals(null, SpanishRegion.OTHER.tmdbLanguageTag())

        assertEquals(
            listOf("ES", "US", "GB", "MX"),
            SpanishRegion.SPAIN.alternativeCountryPriority("ES")
        )
        assertEquals(
            listOf("AR", "MX", "US", "GB", "ES"),
            SpanishRegion.LATAM.alternativeCountryPriority("AR")
        )
    }
}
