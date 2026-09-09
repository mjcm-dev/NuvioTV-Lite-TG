// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.core.telegram

import java.util.Locale

/**
 * Spanish variant bucket used to *rank* Telegram results, never to exclude them.
 *
 * Queries always carry both the Spain and the Latin-American title variants
 * (channels mix them freely); the bucket only decides which variant is ordered
 * first and which TMDB translation / alternative-title country is preferred.
 */
enum class SpanishRegion {
    SPAIN,
    LATAM,
    /** Non-Spanish UI language (e.g. English): keep legacy language behavior. */
    OTHER
}

/**
 * Resolves the bucket with an explicit priority chain so it stays unit-testable:
 *
 * 1. App language override ([appLocaleTag], e.g. "es-ES" from the in-app setting).
 * 2. System locale country ([systemCountry], e.g. from `configuration.locales[0]`).
 * 3. Timezone tiebreak, only when the locale has Spanish language but no country
 *    (bare "es" is common on cheap TV boxes): European zones -> [SPAIN],
 *    American zones -> [LATAM].
 *
 * `es-US` maps to [LATAM]. Any other Spanish-speaking country maps to [LATAM].
 */
fun resolveSpanishRegion(
    appLocaleTag: String?,
    systemLanguage: String,
    systemCountry: String,
    timezoneId: String?
): SpanishRegion {
    val appLocale = parseLocaleTag(appLocaleTag)
    val language = (appLocale?.language ?: systemLanguage)
        .lowercase(Locale.US)
        .let { legacyLanguageCode(it) }
    if (language != "es") return SpanishRegion.OTHER

    val country = (appLocale?.country ?: systemCountry)
        .uppercase(Locale.US)
    if (country.isNotBlank()) {
        return if (country == "ES") SpanishRegion.SPAIN else SpanishRegion.LATAM
    }

    val zone = timezoneId.orEmpty()
    return when {
        // Explicit Spanish zones first; remaining Europe/* falls back to Spain
        // because a bare "es" locale on a European-timezone box is overwhelmingly ES.
        zone == "Atlantic/Canary" || zone == "Africa/Ceuta" -> SpanishRegion.SPAIN
        zone.startsWith("America/") -> SpanishRegion.LATAM
        zone.startsWith("Europe/") -> SpanishRegion.SPAIN
        else -> SpanishRegion.OTHER
    }
}

/** TMDB translation tag preferred for title seeds per bucket. */
fun SpanishRegion.tmdbLanguageTag(): String? = when (this) {
    SpanishRegion.SPAIN -> "es-ES"
    SpanishRegion.LATAM -> "es-MX"
    SpanishRegion.OTHER -> null
}

/**
 * Alternative-title country priority per bucket. TMDB only ships `es-ES` and
 * `es-MX` translations, so for LatAm the Mexican tag plus any LatAm-tagged
 * alternative is the best available signal.
 */
fun SpanishRegion.alternativeCountryPriority(userCountry: String): List<String> {
    val normalizedUser = userCountry.uppercase(Locale.US).takeIf { it.length == 2 }
    return when (this) {
        SpanishRegion.SPAIN ->
            listOfNotNull("ES", "US", "GB", "MX", normalizedUser).distinct()
        SpanishRegion.LATAM ->
            listOfNotNull(normalizedUser, "MX", "US", "GB", "ES").distinct()
        SpanishRegion.OTHER ->
            listOfNotNull(normalizedUser, "US", "GB", "ES", "MX").distinct()
    }
}

private data class ParsedLocaleTag(val language: String, val country: String)

private fun parseLocaleTag(tag: String?): ParsedLocaleTag? {
    val clean = tag?.trim().orEmpty()
    if (clean.isEmpty() || clean == "__UNSET__") return null
    return try {
        val locale = Locale.forLanguageTag(clean)
        val language = locale.language.orEmpty()
        if (language.isBlank() || language == "und") return null
        ParsedLocaleTag(language = language, country = locale.country.orEmpty())
    } catch (_: Exception) {
        null
    }
}

private fun legacyLanguageCode(language: String): String = when (language) {
    "iw" -> "he"
    "in" -> "id"
    "ji" -> "yi"
    else -> language
}
