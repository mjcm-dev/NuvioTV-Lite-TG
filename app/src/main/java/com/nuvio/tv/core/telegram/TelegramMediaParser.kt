// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.core.telegram

/**
 * Turns heterogeneous Spanish/international release file names from Telegram
 * channels into structured media metadata.
 *
 * Input examples handled:
 *  - "Dune.2021.1080p.WEB-DL.Dual.Latino.Castellano.mkv"
 *  - "La Casa de Papel S03E05 1080p NF WEB-DL x264.mkv"
 *  - "La que se avecina 12x09.avi"
 *  - "Cuéntame cómo pasó Temporada 24 Capítulo 3.mp4"
 *  - "Mujeres.T1.E05.mp4"
 */
object TelegramMediaParser {

    data class Parsed(
        val rawName: String,
        val cleanTitle: String,
        val season: Int?,
        val episode: Int?,
        val quality: String?,
        val year: Int?
    )

    private const val MAX_SEASON = 100
    private const val MAX_EPISODE = 2000

    // Ordered by specificity: the first match wins and marks where the title ends.
    // Numeric patterns first (locale-neutral); word-based ones only when localized.
    private val SEASON_EPISODE_PATTERNS_NUMERIC = listOf(
        Regex("""(?:^|[^A-Za-z0-9])S(\d{1,2})[\s._-]?E(\d{1,4})(?![0-9])""", RegexOption.IGNORE_CASE),
        Regex("""(?<![0-9])(\d{1,2})[xX](\d{1,4})(?![0-9p])""")
    )

    private val SEASON_EPISODE_PATTERNS_LOCALIZED = listOf(
        Regex("""Temporada\s+(\d{1,3})[^A-Za-z0-9]{0,20}(?:Cap[ií]tulo|Episodio|\bEp\b|\bCap\b)\s*(\d{1,4})""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|[^A-Za-z0-9])T(\d{1,2})[\s._-]*E(\d{1,4})(?![0-9])""", RegexOption.IGNORE_CASE)
    )

    private val EPISODE_ONLY_PATTERNS = listOf(
        Regex("""(?:^|[^A-Za-z0-9])(?:E|Ep|Episodio|Cap|Capitulo|Capítulo)[\s._-]{0,3}(\d{1,4})(?![0-9])""", RegexOption.IGNORE_CASE)
    )

    private val EPISODE_ONLY_PATTERNS_NUMERIC = listOf(
        Regex("""(?:^|[^A-Za-z0-9])E[\s._-]{0,3}(\d{1,4})(?![0-9])""", RegexOption.IGNORE_CASE)
    )

    private val QUALITY_PATTERN =
        Regex("""(?<![0-9A-Za-z])(2160|1080|720|480|360)[pi](?![0-9A-Za-z])""", RegexOption.IGNORE_CASE)

    private val YEAR_PATTERN = Regex("""(?<![0-9])(19\d{2}|20\d{2})(?![0-9])""")

    private val NOISE_WORDS = setOf(
        "1080p", "720p", "480p", "2160p", "4k", "uhd", "hd", "fullhd", "hdr", "hdr10", "dv",
        "webdl", "web-dl", "web", "webrip", "bdrip", "brrip", "bluray", "blu-ray", "dvdrip",
        "dvd", "hdtv", "hdrip", "remux", "microhd", "x264", "x265", "h264", "h265", "hevc",
        "avc", "aac", "aac2", "aac5", "ac3", "eac3", "dts", "dtshd", "truehd", "atmos",
        "ddp", "dd5", "5ch", "2ch", "mp3", "dual", "latino", "castellano", "espanol",
        "español", "spanish", "ingles", "english", "sub", "subs", "subtitulado", "subtitulada",
        "vose", "vosee", "vosi", "vesre", "netflix", "nf", "amzn", "hmax", "atvp", "dsnp",
        "proper", "repack", "extended", "unrated", "criterion", "imax", "10bit", "8bit",
        "60fps", "hq", "vip", "mkv", "mp4", "avi", "dl", "dd", "ma", "hdrplus"
    )

    private val ARTICLES = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas",
        "the", "a", "an", "de", "del", "al", "of", "and", "y"
    )

    /**
     * Parses a document/file name into structured media info.
     * @param localized when false, only numeric patterns (SxxExx, NxNN, Exx)
     * are recognized; word-based ones (Temporada/Capítulo/Season/Episode/T..E..)
     * belong to the series i18n machinery.
     */
    fun parse(rawName: String, localized: Boolean = true): Parsed {
        val withoutExtension = rawName.substringBeforeLast('.', rawName)
            .replace('_', ' ')
            .replace('.', ' ')

        val seasonEpisodePatterns =
            if (localized) SEASON_EPISODE_PATTERNS_NUMERIC + SEASON_EPISODE_PATTERNS_LOCALIZED
            else SEASON_EPISODE_PATTERNS_NUMERIC
        val serMatch = seasonEpisodePatterns.firstNotNullOfOrNull { it.find(withoutExtension) }
        var season: Int? = null
        var episode: Int? = null
        var titleSource = withoutExtension

        if (serMatch != null) {
            val s = serMatch.groupValues[1].toIntOrNull()?.takeIf { it in 1..MAX_SEASON }
            val e = serMatch.groupValues[2].toIntOrNull()?.takeIf { it in 1..MAX_EPISODE }
            if (s != null && e != null) {
                season = s
                episode = e
                titleSource = if (serMatch.range.first > 0) {
                    withoutExtension.substring(0, serMatch.range.first)
                } else {
                    withoutExtension
                        .substring(serMatch.range.last + 1)
                        .trimStart(' ', '-', '_', '.', ':', '[', ']', '(', ')')
                        .ifBlank { withoutExtension }
                }
            }
        } else {
            val episodeOnlyPatterns =
                if (localized) EPISODE_ONLY_PATTERNS else EPISODE_ONLY_PATTERNS_NUMERIC
            val epMatch = episodeOnlyPatterns.firstNotNullOfOrNull { it.find(withoutExtension) }
            val ep = epMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..MAX_EPISODE }
            if (ep != null) {
                episode = ep
                val cutAt = epMatch.range.first
                if (cutAt > 0) {
                    titleSource = withoutExtension.substring(0, cutAt)
                }
            }
        }

        val quality = QUALITY_PATTERN.find(titleSource)?.value?.lowercase()
            ?: QUALITY_PATTERN.find(withoutExtension)?.value?.lowercase()
            ?: detectUhdKeyword(withoutExtension)

        val year = YEAR_PATTERN.findAll(titleSource).lastOrNull()?.value?.toIntOrNull()

        val cleaned = stripNoise(titleSource)

        return Parsed(
            rawName = rawName,
            cleanTitle = cleaned,
            season = season,
            episode = episode,
            quality = when {
                quality == null -> null
                quality == "2160p" || quality == "2160i" -> "4K"
                else -> quality
            },
            year = year
        )
    }

    /**
     * Normalizes a title for comparison: lowercases, strips diacritics and
     * punctuation, collapses whitespace.
     */
    fun normalizeForMatch(input: String): String =
        java.text.Normalizer.normalize(input.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun detectUhdKeyword(input: String): String? =
        if (Regex("""\b4k\b""", RegexOption.IGNORE_CASE).containsMatchIn(input)) "4K" else null

    private fun stripNoise(source: String): String {
        var text = source.replace(Regex("""[(\[{}].*?[)\]}]"""), " ")

        YEAR_PATTERN.findAll(text).toList().lastOrNull()?.let { text = text.replaceRange(it.range, " ") }
        QUALITY_PATTERN.find(text)?.let { text = text.replaceRange(it.range, " ") }

        text = text.replace(Regex("[\\-–—_]+"), " ")
        val kept = StringBuilder()
        text.split(Regex("\\s+")).forEach { tokenRaw ->
            val token = tokenRaw.trim(',', ';', ':', '-', '.', '!', '¡', '?', '¿')
            if (token.isEmpty()) return@forEach
            val bare = token.trim('\'', '"').lowercase().let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "") }
            if (bare.isEmpty() || bare.length > 30 || bare.all { !it.isLetterOrDigit() }) return@forEach
            // TG-START: keep lone digits — they are usually sequel numbers ("Prada 2",
            // "Rocky 2"); S/E markers were already cut above, so a leftover digit is
            // signal, not noise. Dropping it capped title overlap below threshold.
            // (re-apply on upstream merge)
            // TG-END
            if (bare in NOISE_WORDS) return@forEach
            kept.append(tokenRaw).append(' ')
        }

        return kept.toString().trim().trim('-', ':', ',').ifEmpty {
            source.substringBeforeLast('.', source).replace(Regex("[._]+"), " ").trim()
        }
    }

    /** Tokens used for matching; articles and release-noise dropped on both sides. */
    fun matchTokens(input: String): List<String> =
        normalizeForMatch(input)
            .split(' ')
            .filter { it.isNotEmpty() && it !in ARTICLES && it !in NOISE_WORDS }

    /**
     * Head of a "Title: Subtitle" style name ("Expediente X: Enfréntate al futuro"
     * -> "Expediente X"). Files very often carry only the head, which can never
     * reach the acceptance threshold against the long official title, so the head
     * is searched and matched as an extra variant. Returns null when there is no
     * subtitle part or the head is too short to be discriminative (single token).
     * Only splits on colons/dashes that delimit a subtitle, never inside words
     * ("Spider-Man" stays intact).
     */
    fun subtitleHeadVariant(title: String): String? {
        val head = title
            .split(Regex("""\s*[:–—]\s*|\s+-\s+"""))
            .firstOrNull()
            ?.trim()
            .orEmpty()
        if (head.isEmpty() || head.equals(title.trim(), ignoreCase = true)) return null
        if (matchTokens(head).size < 2) return null
        return head
    }

    /**
     * Tail of a "Title: Subtitle" style name ("Expediente X: Creer es la clave"
     * -> "Creer es la clave"). Some files carry only the subtitle part
     * ("Clave (2008)"). Same splitting and minimum-token rules as the head.
     */
    fun subtitleTailVariant(title: String): String? {
        val parts = title
            .split(Regex("""\s*[:–—]\s*|\s+-\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val tail = parts.last()
        if (tail.equals(title.trim(), ignoreCase = true)) return null
        if (matchTokens(tail).size < 2) return null
        return tail
    }
}
