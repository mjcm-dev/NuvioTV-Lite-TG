// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.data.repository

import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.core.telegram.TelegramClientManager
import com.nuvio.tv.core.telegram.TelegramAuthState
import com.nuvio.tv.core.telegram.TelegramMediaParser
import com.nuvio.tv.core.telegram.TelegramTitleMatcher
import com.nuvio.tv.core.telegram.TelegramStreamProxy
import com.nuvio.tv.data.local.TelegramSearchSettingsDataStore
import com.nuvio.tv.domain.repository.TelegramRepository
import com.nuvio.tv.domain.repository.TelegramStreamResult
import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import java.util.Locale

@Singleton
class TelegramRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientManager: TelegramClientManager,
    private val streamProxy: TelegramStreamProxy,
    private val telegramSearchSettingsDataStore: TelegramSearchSettingsDataStore
) : TelegramRepository {

    companion object {
        private const val TAG = "TelegramRepo"
        private const val SEARCH_LIMIT = 100
        private const val SEARCH_TIMEOUT_MS = 20_000L
        private const val MIN_FILE_SIZE_BYTES_MOVIE = 50L * 1024 * 1024
        private const val MIN_FILE_SIZE_BYTES_SERIES = 20L * 1024 * 1024
        private const val MATCH_THRESHOLD = 0.70
        private const val MAX_TITLES_QUERIED = 6
        private const val MAX_RESULTS = 40
        private const val MAX_QUERY_TERMS = 14
        private const val MAX_QUERY_TERMS_WITH_EPISODE = 18
        private const val FOLDER_FALLBACK_CHAT_SEARCH_LIMIT = 24
        private const val FOLDER_FALLBACK_HISTORY_PAGE_SIZE = 80
        private const val FOLDER_FALLBACK_HISTORY_PAGES = 80
        private const val FOLDER_FALLBACK_CHAT_QUERY_LIMIT = 80
        private const val FOLDER_FALLBACK_CHAT_TITLE_THRESHOLD = 0.72
        private const val FOLDER_FALLBACK_RELAXED_CHAT_TITLE_THRESHOLD = 0.58
        private val SEARCH_STOPWORDS = setOf(
            "the", "a", "an", "of", "and", "to", "in", "on", "for", "with",
            "el", "la", "los", "las", "un", "una", "de", "del", "y", "en"
        )

        private val VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "m2ts", "ts", "3gp"
        )
        private val EXCLUDED_NON_VIDEO_EXTENSIONS = setOf(
            "cbz", "cbr", "cb7", "pdf", "epub", "mobi", "azw", "azw3", "djvu"
        )
    }

    private val chatTitleCache = HashMap<Long, String?>()
    private val chatTitleMutex = Mutex()

    override fun isAvailable(): Boolean =
        clientManager.authState.value is TelegramAuthState.Ready

    override suspend fun searchStreams(
        type: String,
        titles: List<String>,
        releaseYear: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?
    ): List<TelegramStreamResult> {
        if (!isAvailable()) return emptyList()

        val candidateTitles = buildCandidateTitles(titles)
            .filter { it.isNotBlank() }
            .distinctBy { TelegramMediaParser.normalizeForMatch(it) }
            .take(MAX_TITLES_QUERIED)
        if (candidateTitles.isEmpty()) return emptyList()

        val seenFileIds = HashSet<Int>()
        val fallbackSeedChatIds = LinkedHashSet<Long>()
        val seRejectSamples = mutableListOf<String>()
        val results = mutableListOf<TelegramStreamResult>()
        var totalFound = 0
        var rejectedSize = 0
        var rejectedTitle = 0
        var rejectedSeasonEpisode = 0
        var duplicates = 0

        val queryTerms = buildSearchQueries(candidateTitles, imdbId, season, episode)
        Log.d(TAG, "Query terms: ${queryTerms.take(8)}")

        queryLoop@ for (title in queryTerms) {
            if (results.size >= MAX_RESULTS) break
            for (filter in listOf(TdApi.SearchMessagesFilterDocument(), TdApi.SearchMessagesFilterVideo())) {
                if (results.size >= MAX_RESULTS) break@queryLoop
                val request = TdApi.SearchMessages()
                request.query = title
                request.offset = ""
                request.limit = SEARCH_LIMIT
                request.filter = filter

                val found = runCatching {
                    clientManager.sendRequest(request, SEARCH_TIMEOUT_MS)
                }.getOrNull() as? TdApi.FoundMessages
                if (found == null) {
                    Log.w(TAG, "Search failed for \"$title\"")
                    continue
                }

                for (message in found.messages ?: emptyArray()) {
                    if (results.size >= MAX_RESULTS) break
                    totalFound++
                    when (addToResultsIfMatch(
                        message, results, seenFileIds,
                        type, title, candidateTitles, releaseYear, imdbId, season, episode,
                        fromFolderFallback = false,
                        fallbackSearchTerm = null,
                        fallbackChatTitleScore = null,
                        seRejectSamples = seRejectSamples
                    )) {
                        MatchOutcome.ACCEPTED -> Unit
                        MatchOutcome.DUPLICATE -> duplicates++
                        MatchOutcome.REJECTED_SIZE -> rejectedSize++
                        MatchOutcome.REJECTED_TITLE -> rejectedTitle++
                        MatchOutcome.REJECTED_SEASON_EPISODE -> {
                            rejectedSeasonEpisode++
                            if (fallbackSeedChatIds.size < FOLDER_FALLBACK_CHAT_SEARCH_LIMIT) {
                                fallbackSeedChatIds += message.chatId
                            }
                        }
                    }
                }
            }
        }

        val allowChannelContextSeriesMatch = telegramSearchSettingsDataStore.allowChannelContextSeriesMatch.value
        if (results.isEmpty() &&
            allowChannelContextSeriesMatch &&
            type.equals("series", ignoreCase = true) &&
            (season != null || episode != null)
        ) {
            val fallback = searchFolderContextChats(
                titles = candidateTitles,
                type = type,
                releaseYear = releaseYear,
                imdbId = imdbId,
                season = season,
                episode = episode,
                episodeTerms = buildEpisodeTerms(season, episode),
                results = results,
                seenFileIds = seenFileIds,
                seedChatIds = fallbackSeedChatIds.toList(),
                seRejectSamples = seRejectSamples
            )
            totalFound += fallback.found
            rejectedSize += fallback.rejectedSize
            rejectedTitle += fallback.rejectedTitle
            rejectedSeasonEpisode += fallback.rejectedSeasonEpisode
            duplicates += fallback.duplicates
            Log.i(
                TAG,
                "Folder fallback accepted=${fallback.accepted} chats=${fallback.chatsScanned} seeds=${fallbackSeedChatIds.size} " +
                    "found=${fallback.found} se=${fallback.rejectedSeasonEpisode} title=${fallback.rejectedTitle}"
            )
        }

        val preferredLanguage = preferredUiLanguageCode()
        Log.i(
            TAG,
            "Telegram search \"${candidateTitles.first()}\" S=${season ?: '-'} E=${episode ?: '-'}: " +
                "found=$totalFound accepted=${results.size} " +
                "(size=$rejectedSize title=$rejectedTitle se=$rejectedSeasonEpisode dup=$duplicates)"
        )
        if (seRejectSamples.isNotEmpty()) {
            seRejectSamples.take(12).forEachIndexed { index, sample ->
                Log.d(TAG, "SE_REJECT_SAMPLE[$index] $sample")
            }
        }
        return results
            .sortedWith(
                compareByDescending<TelegramStreamResult> { it.matchScore }
                    .thenByDescending { languagePriorityScore(it.fileName, preferredLanguage) }
                    .thenByDescending { qualityRank(it.quality) }
                    .thenByDescending { it.sizeBytes }
            )
    }

    private fun buildCandidateTitles(titles: List<String>): List<String> {
        return titles
            .filter { it.isNotBlank() }
            .distinctBy { TelegramMediaParser.normalizeForMatch(it) }
    }

    private fun buildSearchQueries(
        candidateTitles: List<String>,
        imdbId: String?,
        season: Int?,
        episode: Int?
    ): List<String> {
        val normalizedImdbId = imdbId
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.matches(Regex("""tt\d{7,9}""")) }

        val episodeTerms = buildEpisodeTerms(season, episode)
        val maxQueryTerms = if (episodeTerms.isNotEmpty()) {
            MAX_QUERY_TERMS_WITH_EPISODE
        } else {
            MAX_QUERY_TERMS
        }
        val episodeBoostQueries = buildEpisodeBoostQueries(
            candidateTitles = candidateTitles,
            episodeTerms = episodeTerms,
            normalizedImdbId = normalizedImdbId
        )

        val perTitleVariants = candidateTitles.map { title ->
            queryVariants(title, episodeTerms)
        }

        if (normalizedImdbId == null) {
            val interleaved = buildInterleavedQueries(perTitleVariants, maxQueryTerms)
            return (episodeBoostQueries + interleaved)
                .distinctBy { TelegramMediaParser.normalizeForMatch(it) }
                .take(maxQueryTerms)
        }

        val imdbAwarePerTitle = perTitleVariants.map { variants ->
            buildList {
                for (variant in variants) {
                    add("$variant $normalizedImdbId")
                    add(variant)
                }
            }
        }

        val interleaved = buildInterleavedQueries(imdbAwarePerTitle, maxQueryTerms)
        return (episodeBoostQueries + interleaved + normalizedImdbId)
            .distinctBy { TelegramMediaParser.normalizeForMatch(it) }
            .take(maxQueryTerms)
    }

    private fun queryVariants(rawTitle: String, episodeTerms: List<String>): List<String> {
        val base = rawTitle.trim()
        if (base.isBlank()) return emptyList()

        val variants = LinkedHashSet<String>()
        variants += base

        titleAcronym(base)?.let { variants += it }

        val withoutYear = base.replace(Regex("""\b(19|20)\d{2}\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (withoutYear.isNotBlank()) variants += withoutYear

        val withoutSequelMarker = withoutYear.replace(
            Regex("""\s+(?:part\s+)?(?:\d+|[ivxlcdm]{1,6})$""", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        if (withoutSequelMarker.isNotBlank()) variants += withoutSequelMarker

        val normalizedTokens = TelegramMediaParser.normalizeForMatch(withoutSequelMarker)
            .split(' ')
            .filter { token -> token.isNotBlank() && token.length > 2 && token !in SEARCH_STOPWORDS }

        if (normalizedTokens.size >= 2) {
            variants += normalizedTokens.joinToString(" ")
            variants += normalizedTokens.takeLast(2).joinToString(" ")
        }

        if (episodeTerms.isNotEmpty()) {
            val baseVariants = variants.toList().take(2)
            val episodeVariants = episodeTerms.take(3)
            for (variant in baseVariants) {
                for (episodeTerm in episodeVariants) {
                    variants += "$variant $episodeTerm"
                }
            }
        }

        return variants.toList()
    }

    private fun titleAcronym(title: String): String? {
        val initials = title
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .mapNotNull { token -> token.firstOrNull()?.uppercaseChar() }
            .joinToString("")
        return initials
            .takeIf { it.length in 2..8 }
            ?.takeUnless { it.equals(title, ignoreCase = true) }
    }

    private fun buildEpisodeTerms(season: Int?, episode: Int?): List<String> {
        val s = season?.takeIf { it > 0 }
        val e = episode?.takeIf { it > 0 }
        if (s == null || e == null) return emptyList()

        val season2 = s.toString().padStart(2, '0')
        val episode2 = e.toString().padStart(2, '0')

        return linkedSetOf(
            "S${season2}E${episode2}",
            "S${s}E${e}",
            "${s}x${episode2}",
            "${season2}x${episode2}",
            "E${episode2}",
            "Ep ${e}",
            "Cap ${e}"
        ).toList()
    }

    private fun buildEpisodeBoostQueries(
        candidateTitles: List<String>,
        episodeTerms: List<String>,
        normalizedImdbId: String?
    ): List<String> {
        if (candidateTitles.isEmpty() || episodeTerms.isEmpty()) return emptyList()
        val titleSeeds = candidateTitles.take(3)
        val episodeSeeds = episodeTerms.take(3)
        val queries = LinkedHashSet<String>()

        for (title in titleSeeds) {
            for (episodeTerm in episodeSeeds) {
                queries += "$title $episodeTerm"
                if (!normalizedImdbId.isNullOrBlank()) {
                    queries += "$title $episodeTerm $normalizedImdbId"
                }
            }
        }

        return queries.toList()
    }

    private fun buildInterleavedQueries(
        perTitleVariants: List<List<String>>,
        maxQueries: Int
    ): List<String> {
        val dedup = LinkedHashSet<String>()
        var index = 0

        while (dedup.size < maxQueries) {
            var addedInRound = false
            for (variants in perTitleVariants) {
                if (index >= variants.size) continue
                val query = variants[index]
                val normalized = TelegramMediaParser.normalizeForMatch(query)
                if (normalized.isNotBlank() && dedup.add(query)) {
                    addedInRound = true
                    if (dedup.size >= maxQueries) break
                }
            }
            if (!addedInRound) break
            index++
        }

        return dedup.toList()
    }

    private enum class MatchOutcome { ACCEPTED, DUPLICATE, REJECTED_SIZE, REJECTED_TITLE, REJECTED_SEASON_EPISODE }

    private suspend fun addToResultsIfMatch(
        message: TdApi.Message,
        results: MutableList<TelegramStreamResult>,
        seenFileIds: MutableSet<Int>,
        type: String,
        queryTerm: String,
        titles: List<String>,
        releaseYear: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        fromFolderFallback: Boolean,
        fallbackSearchTerm: String?,
        fallbackChatTitleScore: Double?,
        seRejectSamples: MutableList<String>
    ): MatchOutcome {
        val extracted = extractFile(message) ?: return MatchOutcome.REJECTED_SIZE
        val minRequiredSize = if (type.equals("series", ignoreCase = true)) {
            MIN_FILE_SIZE_BYTES_SERIES
        } else {
            MIN_FILE_SIZE_BYTES_MOVIE
        }
        if (extracted.sizeBytes < minRequiredSize) return MatchOutcome.REJECTED_SIZE
        if (!seenFileIds.add(extracted.fileId)) return MatchOutcome.DUPLICATE

        val matchText = buildMatchText(message, extracted.fileName)
        val parsed = TelegramMediaParser.parse(matchText)
        val normalizedImdbId = imdbId
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.matches(Regex("""tt\d{7,9}""")) }
        val normalizedQueryTerm = TelegramMediaParser.normalizeForMatch(queryTerm)
        val isImdbOnlyQuery = normalizedImdbId != null && normalizedQueryTerm == normalizedImdbId
        val normalizedFileName = TelegramMediaParser.normalizeForMatch(matchText)
        val hasImdbTag = normalizedImdbId != null && normalizedFileName.contains(normalizedImdbId)
        val allowChannelContextSeriesMatch = telegramSearchSettingsDataStore.allowChannelContextSeriesMatch.value

        val inferredEpisode = inferEpisodeFromBareNumber(matchText)
        val compactSeasonEpisode = if (fromFolderFallback && season != null) {
            inferEpisodeFromCompactSeasonToken(matchText, season)
        } else {
            null
        }
        val effectiveSeason = parsed.season ?: if (compactSeasonEpisode != null) season else null
        val effectiveEpisode = parsed.episode ?: inferredEpisode ?: compactSeasonEpisode
        val hasRequestedSeasonToken = season?.let { requestedSeason ->
            containsRequestedSeasonToken(matchText, requestedSeason) ||
                compactSeasonEpisode != null
        } == true

        fun rejectSeasonEpisode(reason: String): MatchOutcome {
            if (seRejectSamples.size < 24 && fromFolderFallback) {
                seRejectSamples +=
                    "reason=$reason term=${fallbackSearchTerm ?: "-"} chatScore=${"%.3f".format(Locale.US, fallbackChatTitleScore ?: -1.0)} file=${extracted.fileName} " +
                        "req=${season ?: '-'}x${episode ?: '-'} parsed=${parsed.season ?: '-'}x${parsed.episode ?: '-'} " +
                        "effective=${effectiveSeason ?: '-'}x${effectiveEpisode ?: '-'} compact=${compactSeasonEpisode ?: '-'}"
            }
            return MatchOutcome.REJECTED_SEASON_EPISODE
        }

        val hasExactRequestedToken = if (season != null && episode != null) {
            containsExactSeasonEpisodeToken(matchText, season, episode)
        } else {
            false
        }

        if (
            fromFolderFallback &&
            season != null &&
            episode != null &&
            hasExactRequestedToken &&
            (fallbackChatTitleScore ?: 0.0) >= FOLDER_FALLBACK_RELAXED_CHAT_TITLE_THRESHOLD
        ) {
            // Hard override for folder-style naming where parser may fail on noisy labels.
            return acceptResult(results, extracted, parsed, hasImdbTag = false, isImdbOnlyQuery = false, score = MATCH_THRESHOLD)
        }

        when (type.lowercase()) {
            "series" -> {
                if (season != null && effectiveSeason == null) {
                    val canRelaxSeason =
                        fromFolderFallback &&
                            (hasRequestedSeasonToken || hasExactRequestedToken) &&
                            episode != null &&
                            effectiveEpisode != null &&
                            effectiveEpisode == episode
                    if (!canRelaxSeason) return rejectSeasonEpisode("missing-season")
                }
                if (episode != null && effectiveEpisode == null) {
                    return rejectSeasonEpisode("missing-episode")
                }
                if (season != null && effectiveSeason != null && effectiveSeason != season) {
                    return rejectSeasonEpisode("season-mismatch")
                }
                if (episode != null && effectiveEpisode != null && effectiveEpisode != episode) {
                    return rejectSeasonEpisode("episode-mismatch")
                }
            }
            else -> {
                if (parsed.year != null && releaseYear != null &&
                    abs(parsed.year - releaseYear) > 1
                ) return MatchOutcome.REJECTED_TITLE
            }
        }

        val score = TelegramTitleMatcher.bestScore(titles, parsed.cleanTitle)
        var channelContextScore = 0.0
        val titleAccepted = isImdbOnlyQuery || hasImdbTag || score >= MATCH_THRESHOLD
        if (!titleAccepted) {
            val isSeries = type.equals("series", ignoreCase = true)
            val hasRequestedEpisode = season != null || episode != null
            val hasParsedEpisodeMarkers = effectiveSeason != null || effectiveEpisode != null
            val canUseChannelContext =
                allowChannelContextSeriesMatch &&
                    isSeries &&
                    hasRequestedEpisode &&
                    hasParsedEpisodeMarkers

            if (!canUseChannelContext) return MatchOutcome.REJECTED_TITLE

            val resolvedChatTitle = chatTitle(message.chatId).orEmpty()
            channelContextScore = TelegramTitleMatcher.bestScore(titles, resolvedChatTitle)
            if (channelContextScore < MATCH_THRESHOLD) return MatchOutcome.REJECTED_TITLE

            Log.d(
                TAG,
                "Context accept chatId=${message.chatId} fileId=${extracted.fileId} " +
                    "titleScore=%.3f chatScore=%.3f parsedS=%s parsedE=%s".format(
                        Locale.US,
                        score,
                        channelContextScore,
                        effectiveSeason?.toString() ?: "-",
                        effectiveEpisode?.toString() ?: "-"
                    )
            )
        }

        return acceptResult(
            results = results,
            extracted = extracted,
            parsed = parsed,
            hasImdbTag = hasImdbTag,
            isImdbOnlyQuery = isImdbOnlyQuery,
            score = score,
            channelContextScore = channelContextScore
        )
    }

    private fun acceptResult(
        results: MutableList<TelegramStreamResult>,
        extracted: TelegramStreamResult,
        parsed: TelegramMediaParser.Parsed,
        hasImdbTag: Boolean,
        isImdbOnlyQuery: Boolean,
        score: Double,
        channelContextScore: Double = 0.0
    ): MatchOutcome {
        results += extracted.copy(
            matchScore = when {
                hasImdbTag -> maxOf(score, 0.95)
                isImdbOnlyQuery -> maxOf(score, 0.85)
                channelContextScore > 0.0 -> maxOf(score, (channelContextScore * 0.92).coerceAtLeast(MATCH_THRESHOLD))
                else -> score
            },
            quality = parsed.quality
        )
        return MatchOutcome.ACCEPTED
    }

    private fun buildMatchText(message: TdApi.Message, fileName: String): String {
        val caption = when (val content = message.content) {
            is TdApi.MessageDocument -> content.caption?.text
            is TdApi.MessageVideo -> content.caption?.text
            else -> null
        }.orEmpty().trim()

        return when {
            fileName.isBlank() && caption.isNotBlank() -> caption
            fileName.isNotBlank() && caption.isNotBlank() -> "$fileName $caption"
            else -> fileName
        }
    }

    private fun inferEpisodeFromBareNumber(fileName: String): Int? {
        val baseName = fileName.substringBeforeLast('.', fileName)
            .replace('_', ' ')
            .replace('.', ' ')

        val startsWithEpisode = Regex("""^\s*0?(\d{1,3})(?:\D|$)""")
            .find(baseName)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in 1..200 }
        if (startsWithEpisode != null) return startsWithEpisode

        val episodeCandidates = Regex("""(?:^|\D)0?(\d{1,3})(?:\D|$)""")
            .findAll(baseName)
            .mapNotNull { match ->
                match.groupValues.getOrNull(1)
                    ?.toIntOrNull()
                    ?.takeIf { it in 1..200 }
            }
            .toList()

        return if (episodeCandidates.size == 1) episodeCandidates.first() else null
    }

    private fun containsRequestedSeasonToken(fileName: String, season: Int): Boolean {
        val s = season.toString()
        val s2 = season.toString().padStart(2, '0')
        val normalized = TelegramMediaParser.normalizeForMatch(fileName)

        val seasonTokens = listOf(
            Regex("""\bs$s\b""", RegexOption.IGNORE_CASE),
            Regex("""\bs$s2\b""", RegexOption.IGNORE_CASE),
            Regex("""\bt$s\b""", RegexOption.IGNORE_CASE),
            Regex("""\bt$s2\b""", RegexOption.IGNORE_CASE),
            Regex("""\b$s\s*x\s*\d{1,3}\b""", RegexOption.IGNORE_CASE),
            Regex("""\b$s2\s*x\s*\d{1,3}\b""", RegexOption.IGNORE_CASE),
            Regex("""\btemporada\s+$s\b""", RegexOption.IGNORE_CASE),
            Regex("""\bseason\s+$s\b""", RegexOption.IGNORE_CASE)
        )

        return seasonTokens.any { token -> token.containsMatchIn(normalized) }
    }

    private fun inferEpisodeFromCompactSeasonToken(fileName: String, season: Int): Int? {
        val tokens = TelegramMediaParser.normalizeForMatch(fileName)
            .split(' ')
            .filter { token -> token.length in 3..4 && token.all { it.isDigit() } }

        if (tokens.isEmpty()) return null

        val season1 = season.toString()
        val season2 = season.toString().padStart(2, '0')
        val candidates = tokens.mapNotNull { token ->
            when {
                token.length == 3 && token.startsWith(season1) -> {
                    token.takeLast(2).toIntOrNull()?.takeIf { it in 1..200 }
                }
                token.length == 4 && token.startsWith(season2) -> {
                    token.takeLast(2).toIntOrNull()?.takeIf { it in 1..200 }
                }
                else -> null
            }
        }.distinct()

        return if (candidates.size == 1) candidates.first() else null
    }

    private fun containsExactSeasonEpisodeToken(text: String, season: Int, episode: Int): Boolean {
        val s = season.toString()
        val s2 = season.toString().padStart(2, '0')
        val e = episode.toString()
        val e2 = episode.toString().padStart(2, '0')
        val normalized = TelegramMediaParser.normalizeForMatch(text)
        val patterns = listOf(
            Regex("""\b$s\s*x\s*$e2\b""", RegexOption.IGNORE_CASE),
            Regex("""\b$s\s*x\s*$e\b""", RegexOption.IGNORE_CASE),
            Regex("""\bs$s2\s*e$e2\b""", RegexOption.IGNORE_CASE),
            Regex("""\bs$s\s*e$e\b""", RegexOption.IGNORE_CASE),
            Regex("""\bt$s2\s*e$e2\b""", RegexOption.IGNORE_CASE)
        )
        return patterns.any { it.containsMatchIn(normalized) }
    }

    private data class FolderFallbackStats(
        val chatsScanned: Int = 0,
        val found: Int = 0,
        val accepted: Int = 0,
        val rejectedSize: Int = 0,
        val rejectedTitle: Int = 0,
        val rejectedSeasonEpisode: Int = 0,
        val duplicates: Int = 0
    )

    private suspend fun searchFolderContextChats(
        titles: List<String>,
        type: String,
        releaseYear: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        episodeTerms: List<String>,
        results: MutableList<TelegramStreamResult>,
        seenFileIds: MutableSet<Int>,
        seedChatIds: List<Long>,
        seRejectSamples: MutableList<String>
    ): FolderFallbackStats {
        val candidateChatIds = buildList {
            seedChatIds.filter { it != 0L }.forEach { add(it) }
            findCandidateChatsByTitle(titles).forEach { add(it) }
        }.distinct().take(FOLDER_FALLBACK_CHAT_SEARCH_LIMIT)

        if (candidateChatIds.isEmpty()) return FolderFallbackStats()

        val scoredCandidateChats = candidateChatIds.mapNotNull { chatId ->
            val title = chatTitle(chatId).orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val score = TelegramTitleMatcher.bestScore(titles, title)
            chatId to score
        }.sortedByDescending { (_, score) -> score }
        if (scoredCandidateChats.isEmpty()) return FolderFallbackStats()

        suspend fun scanChats(chats: List<Pair<Long, Double>>): FolderFallbackStats {
            var localStats = FolderFallbackStats(chatsScanned = chats.size)
            for ((chatId, chatScore) in chats) {
                if (results.size >= MAX_RESULTS) break
                val chatQueryTerm = chatTitle(chatId).orEmpty()

                val searchTerms = buildChatEpisodeSearchTerms(season, episode, episodeTerms)
                for (searchTerm in searchTerms) {
                    if (results.size >= MAX_RESULTS) break
                    for (filter in listOf(TdApi.SearchMessagesFilterDocument(), TdApi.SearchMessagesFilterVideo())) {
                        if (results.size >= MAX_RESULTS) break
                        val request = TdApi.SearchChatMessages().apply {
                            this.chatId = chatId
                            this.query = searchTerm
                            this.senderId = null
                            this.fromMessageId = 0L
                            this.offset = 0
                            this.limit = FOLDER_FALLBACK_CHAT_QUERY_LIMIT
                            this.filter = filter
                        }
                        val batch = runCatching {
                            clientManager.sendRequest(request, SEARCH_TIMEOUT_MS)
                        }.getOrNull() as? TdApi.FoundChatMessages ?: continue

                        val messages = batch.messages ?: emptyArray()
                        for (message in messages) {
                            if (results.size >= MAX_RESULTS) break
                            val outcome = addToResultsIfMatch(
                                message = message,
                                results = results,
                                seenFileIds = seenFileIds,
                                type = type,
                                queryTerm = chatQueryTerm,
                                titles = titles,
                                releaseYear = releaseYear,
                                imdbId = imdbId,
                                season = season,
                                episode = episode,
                                fromFolderFallback = true,
                                fallbackSearchTerm = searchTerm,
                                fallbackChatTitleScore = chatScore,
                                seRejectSamples = seRejectSamples
                            )
                            localStats = when (outcome) {
                                MatchOutcome.ACCEPTED -> localStats.copy(found = localStats.found + 1, accepted = localStats.accepted + 1)
                                MatchOutcome.DUPLICATE -> localStats.copy(found = localStats.found + 1, duplicates = localStats.duplicates + 1)
                                MatchOutcome.REJECTED_SIZE -> localStats.copy(found = localStats.found + 1, rejectedSize = localStats.rejectedSize + 1)
                                MatchOutcome.REJECTED_TITLE -> localStats.copy(found = localStats.found + 1, rejectedTitle = localStats.rejectedTitle + 1)
                                MatchOutcome.REJECTED_SEASON_EPISODE -> localStats.copy(found = localStats.found + 1, rejectedSeasonEpisode = localStats.rejectedSeasonEpisode + 1)
                            }
                        }
                    }
                }

                var fromMessageId = 0L
                repeat(FOLDER_FALLBACK_HISTORY_PAGES) {
                    if (results.size >= MAX_RESULTS) return@repeat

                    val request = TdApi.GetChatHistory().apply {
                        this.chatId = chatId
                        this.fromMessageId = fromMessageId
                        this.offset = 0
                        this.limit = FOLDER_FALLBACK_HISTORY_PAGE_SIZE
                        this.onlyLocal = false
                    }

                    val batch = runCatching {
                        clientManager.sendRequest(request, SEARCH_TIMEOUT_MS)
                    }.getOrNull() as? TdApi.Messages ?: return@repeat

                    val messages = batch.messages ?: emptyArray()
                    if (messages.isEmpty()) return@repeat

                    for (message in messages) {
                        if (results.size >= MAX_RESULTS) break
                        val outcome = addToResultsIfMatch(
                            message = message,
                            results = results,
                            seenFileIds = seenFileIds,
                            type = type,
                            queryTerm = chatQueryTerm,
                            titles = titles,
                            releaseYear = releaseYear,
                            imdbId = imdbId,
                            season = season,
                            episode = episode,
                            fromFolderFallback = true,
                            fallbackSearchTerm = chatQueryTerm,
                            fallbackChatTitleScore = chatScore,
                            seRejectSamples = seRejectSamples
                        )
                        localStats = when (outcome) {
                            MatchOutcome.ACCEPTED -> localStats.copy(found = localStats.found + 1, accepted = localStats.accepted + 1)
                            MatchOutcome.DUPLICATE -> localStats.copy(found = localStats.found + 1, duplicates = localStats.duplicates + 1)
                            MatchOutcome.REJECTED_SIZE -> localStats.copy(found = localStats.found + 1, rejectedSize = localStats.rejectedSize + 1)
                            MatchOutcome.REJECTED_TITLE -> localStats.copy(found = localStats.found + 1, rejectedTitle = localStats.rejectedTitle + 1)
                            MatchOutcome.REJECTED_SEASON_EPISODE -> localStats.copy(found = localStats.found + 1, rejectedSeasonEpisode = localStats.rejectedSeasonEpisode + 1)
                        }
                    }

                    fromMessageId = messages.lastOrNull()?.id ?: 0L
                    if (fromMessageId == 0L) return@repeat
                }
            }
            return localStats
        }

        val strictChats = scoredCandidateChats.filter { (_, score) ->
            score >= FOLDER_FALLBACK_CHAT_TITLE_THRESHOLD
        }
        val primaryChats = if (strictChats.isNotEmpty()) {
            strictChats
        } else {
            scoredCandidateChats.filter { (_, score) ->
                score >= FOLDER_FALLBACK_RELAXED_CHAT_TITLE_THRESHOLD
            }
        }
        if (primaryChats.isEmpty()) return FolderFallbackStats()

        if (primaryChats.isNotEmpty()) {
            val preview = primaryChats
                .take(6)
                .joinToString(",") { (chatId, score) -> "$chatId@${"%.2f".format(Locale.US, score)}" }
            Log.d(TAG, "Folder fallback chats: $preview")
        }
        var stats = scanChats(primaryChats)

        if (results.isEmpty()) {
            val scannedChatIds = primaryChats.map { (chatId, _) -> chatId }.toHashSet()
            val retryChats = scoredCandidateChats
                .filter { (chatId, score) ->
                    score >= FOLDER_FALLBACK_RELAXED_CHAT_TITLE_THRESHOLD && chatId !in scannedChatIds
                }
                .take(FOLDER_FALLBACK_CHAT_SEARCH_LIMIT)

            if (retryChats.isNotEmpty()) {
                val retryPreview = retryChats
                    .take(6)
                    .joinToString(",") { (chatId, score) -> "$chatId@${"%.2f".format(Locale.US, score)}" }
                Log.d(TAG, "Folder fallback retry chats: $retryPreview")

                val retryStats = scanChats(retryChats)
                stats = FolderFallbackStats(
                    chatsScanned = stats.chatsScanned + retryStats.chatsScanned,
                    found = stats.found + retryStats.found,
                    accepted = stats.accepted + retryStats.accepted,
                    rejectedSize = stats.rejectedSize + retryStats.rejectedSize,
                    rejectedTitle = stats.rejectedTitle + retryStats.rejectedTitle,
                    rejectedSeasonEpisode = stats.rejectedSeasonEpisode + retryStats.rejectedSeasonEpisode,
                    duplicates = stats.duplicates + retryStats.duplicates
                )
            }
        }

        return stats
    }

    private fun buildChatEpisodeSearchTerms(
        season: Int?,
        episode: Int?,
        episodeTerms: List<String>
    ): List<String> {
        val s = season?.takeIf { it > 0 }
        val e = episode?.takeIf { it > 0 }
        if (s == null || e == null) return episodeTerms.take(4)

        val season2 = s.toString().padStart(2, '0')
        val episode2 = e.toString().padStart(2, '0')
        return linkedSetOf(
            "${s}x$episode2",
            "${s}x$e",
            "S${season2}E${episode2}",
            "S${s}E${e}",
            "T${season2}E${episode2}"
        ).toList()
    }

    private suspend fun findCandidateChatsByTitle(titles: List<String>): List<Long> {
        val collected = LinkedHashSet<Long>()
        val searchTitles = titles
            .asSequence()
            .flatMap { title -> sequenceOf(title, TelegramMediaParser.normalizeForMatch(title)) }
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
            .take(6)
            .toList()

        for (query in searchTitles) {
            if (collected.size >= FOLDER_FALLBACK_CHAT_SEARCH_LIMIT) break
            val request = TdApi.SearchChats().apply {
                this.query = query
                this.limit = FOLDER_FALLBACK_CHAT_SEARCH_LIMIT
            }
            val chats = runCatching {
                clientManager.sendRequest(request, 8_000L)
            }.getOrNull() as? TdApi.Chats ?: continue
            chats.chatIds?.forEach { chatId ->
                if (collected.size < FOLDER_FALLBACK_CHAT_SEARCH_LIMIT) {
                    collected += chatId
                }
            }
        }

        return collected
            .mapNotNull { chatId ->
                val chatName = chatTitle(chatId).orEmpty()
                if (chatName.isBlank()) return@mapNotNull null
                val score = TelegramTitleMatcher.bestScore(titles, chatName)
                chatId to score
            }
            .sortedByDescending { (_, score) -> score }
            .map { (chatId, _) -> chatId }
            .take(FOLDER_FALLBACK_CHAT_SEARCH_LIMIT)
    }

    private fun extractFile(message: TdApi.Message): TelegramStreamResult? {
        val fileName: String
        val file: TdApi.File
        var mimeType: String? = null
        when (val content = message.content) {
            is TdApi.MessageDocument -> {
                val document = content.document ?: return null
                fileName = document.fileName.orEmpty()
                file = document.document ?: return null
                mimeType = document.mimeType
                if (!isPlayableVideoDocument(fileName, mimeType, message.id)) return null
            }
            is TdApi.MessageVideo -> {
                val video = content.video ?: return null
                fileName = video.fileName.orEmpty()
                file = video.video ?: return null
            }
            else -> return null
        }
        if (fileName.isBlank()) return null

        val size = file.size.takeIf { it > 0 } ?: file.expectedSize
        if (size <= 0L) return null

        return TelegramStreamResult(
            chatId = message.chatId,
            messageId = message.id,
            fileId = file.id,
            fileName = fileName,
            sizeBytes = size,
            quality = null,
            year = null,
            matchScore = 0.0,
            chatTitle = null
        )
    }

    private fun isPlayableVideoDocument(fileName: String, mimeType: String?, messageId: Long): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            if (ext in EXCLUDED_NON_VIDEO_EXTENSIONS) {
                Log.d(TAG, "Reject non-video document: ext=$ext file=$fileName messageId=$messageId")
                return false
            }
            if (ext in VIDEO_EXTENSIONS) return true
        }
        val mime = mimeType?.lowercase().orEmpty()
        if (mime.startsWith("video/")) return true
        if (mime.startsWith("application/")) {
            val accepted = ext in VIDEO_EXTENSIONS
            if (!accepted) {
                Log.d(TAG, "Reject application document: mime=$mime ext=$ext file=$fileName messageId=$messageId")
            }
            return accepted
        }
        return false
    }

    private fun preferredUiLanguageCode(): String {
        val locale = context.resources.configuration.locales.get(0)
        return locale?.language?.lowercase(Locale.US).orEmpty()
    }

    private fun languagePriorityScore(fileName: String, preferredLanguageCode: String): Int {
        val normalized = TelegramMediaParser.normalizeForMatch(fileName)
        val hasSpanish = containsAny(normalized, listOf("castellano", "espanol", "spanish", "latino"))
        val hasCastilian = containsAny(normalized, listOf("castellano", "espana", "espanol espana"))
        val hasLatam = containsAny(normalized, listOf("latino", "latam"))
        val hasEnglish = containsAny(normalized, listOf("english", "ingles"))
        val isDual = containsAny(normalized, listOf("dual", "multi audio", "multiaudio"))

        return when (preferredLanguageCode) {
            "es" -> when {
                hasCastilian -> 40
                hasSpanish -> 34
                hasLatam -> 30
                isDual && (hasSpanish || hasEnglish) -> 26
                isDual -> 22
                hasEnglish -> 10
                else -> 0
            }
            "en" -> when {
                hasEnglish -> 40
                isDual && hasEnglish -> 30
                isDual -> 20
                hasSpanish -> 8
                else -> 0
            }
            else -> when {
                isDual -> 24
                hasEnglish || hasSpanish -> 16
                else -> 0
            }
        }
    }

    private fun containsAny(normalizedText: String, terms: List<String>): Boolean =
        terms.any { term -> normalizedText.contains(term) }

    private fun qualityRank(quality: String?): Int {
        val q = quality?.lowercase() ?: return -1
        return when {
            q.contains("4k") || q.contains("2160") -> 2160
            q.contains("1080") -> 1080
            q.contains("720") -> 720
            q.contains("480") -> 480
            q.contains("360") -> 360
            else -> -1
        }
    }

    /** Resolves and caches a human-readable chat name; never throws. */
    override suspend fun chatTitle(chatId: Long): String? {
        chatTitleMutex.withLock {
            if (chatTitleCache.containsKey(chatId)) return chatTitleCache[chatId]
        }
        val title = runCatching {
            val request = TdApi.GetChat()
            request.chatId = chatId
            (clientManager.sendRequest(request, 10_000L) as? TdApi.Chat)?.title
        }.getOrNull()
        chatTitleMutex.withLock {
            chatTitleCache[chatId] = title
        }
        return title
    }
}
