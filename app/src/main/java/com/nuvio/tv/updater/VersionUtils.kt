package com.nuvio.tv.updater

internal data class SemanticVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val prerelease: List<String>
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }

        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1

        val sharedSize = minOf(prerelease.size, other.prerelease.size)
        for (index in 0 until sharedSize) {
            comparePrereleaseIdentifier(
                prerelease[index],
                other.prerelease[index]
            ).takeIf { it != 0 }?.let { return it }
        }
        return compareValues(prerelease.size, other.prerelease.size)
    }

    private fun comparePrereleaseIdentifier(left: String, right: String): Int {
        val leftNumeric = left.all(Char::isDigit)
        val rightNumeric = right.all(Char::isDigit)

        if (leftNumeric && rightNumeric) {
            val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
            val normalizedRight = right.trimStart('0').ifEmpty { "0" }
            compareValues(normalizedLeft.length, normalizedRight.length)
                .takeIf { it != 0 }
                ?.let { return it }
            return normalizedLeft.compareTo(normalizedRight)
        }
        if (leftNumeric) return -1
        if (rightNumeric) return 1
        return left.compareTo(right)
    }
}

internal object VersionUtils {
    private val versionPattern = Regex(
        "^(\\d+)\\.(\\d+)\\.(\\d+)" +
            "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
            "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
    )

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            // "-lite" marks the edition, not a prerelease. Every Lite versionName and release tag
            // carries it, so leaving it in the prerelease identifiers makes every Lite release look
            // like a prerelease: the STABLE update channel then finds nothing eligible, ever.
            .removeSuffix("-lite")
    }

    fun parse(raw: String?): SemanticVersion? {
        val match = versionPattern.matchEntire(normalize(raw)) ?: return null
        return SemanticVersion(
            major = match.groupValues[1].toLongOrNull() ?: return null,
            minor = match.groupValues[2].toLongOrNull() ?: return null,
            patch = match.groupValues[3].toLongOrNull() ?: return null,
            prerelease = match.groupValues[4]
                .takeIf(String::isNotEmpty)
                ?.split('.')
                .orEmpty()
        )
    }

    fun isPrerelease(raw: String?): Boolean = parse(raw)?.prerelease?.isNotEmpty() == true

    // TG-START: trailing -tg.N release iteration as a build number (re-apply on upstream merge)
    private val tgIterationSuffix = Regex("""-tg\.(\d+)$""")

    /** Trailing `-tg.N` iteration of a normalized TG version, or null when absent. */
    fun tgIterationOf(normalized: String): Long? =
        tgIterationSuffix.find(normalized)?.groupValues?.get(1)?.toLongOrNull()
    // TG-END

    fun isRemoteNewer(remote: String?, local: String?): Boolean {
        val remoteVersion = parse(remote) ?: return false
        val localVersion = parse(local) ?: return false
        // TG-START: same TG base compares by -tg.N iteration, never re-offer own release (re-apply on upstream merge)
        val remoteNormalized = normalize(remote)
        val localNormalized = normalize(local)
        val remoteBase = remoteNormalized.replace(tgIterationSuffix, "-tg")
        val localBase = localNormalized.replace(tgIterationSuffix, "-tg")
        if (remoteBase.contains("-tg") && remoteBase == localBase) {
            val remoteIteration = tgIterationOf(remoteNormalized) ?: 0L
            val localIteration = tgIterationOf(localNormalized) ?: 0L
            return remoteIteration > localIteration
        }
        // TG-END
        return remoteVersion > localVersion
    }
}
