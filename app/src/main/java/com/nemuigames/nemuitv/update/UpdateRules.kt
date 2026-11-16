package com.nemuigames.nemuitv.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A three-part numeric version; compares field by field so 1.10.0 > 1.9.9. */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
}

private val VERSION_REGEX = Regex("""^v?(\d+)\.(\d+)\.(\d+)""")

/** "v1.2.0" and "1.2.0-debug" both give 1.2.0; anything else is null. */
fun parseVersion(s: String): Version? {
    val m = VERSION_REGEX.find(s.trim()) ?: return null
    val (major, minor, patch) = m.destructured
    return Version(
        major.toIntOrNull() ?: return null,
        minor.toIntOrNull() ?: return null,
        patch.toIntOrNull() ?: return null,
    )
}

/** What the installer needs from a GitHub release. */
data class ReleaseInfo(
    val version: Version,
    val versionText: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val shaUrl: String,
)

@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val body: String? = null,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
private data class AssetDto(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String = "",
)

private val releaseJson = Json { ignoreUnknownKeys = true }

/**
 * Parses GitHub's "latest release" JSON. Assets are matched by the exact names
 * built from the tag, so stray files (zips, older APKs) can never be picked.
 */
fun parseLatestRelease(body: String): ReleaseInfo? {
    val dto = try {
        releaseJson.decodeFromString<ReleaseDto>(body)
    } catch (e: Exception) {
        return null
    }
    val version = parseVersion(dto.tagName) ?: return null
    val text = "${version.major}.${version.minor}.${version.patch}"
    val apk = dto.assets.firstOrNull { it.name == "NemuiTV-$text.apk" } ?: return null
    val sha = dto.assets.firstOrNull { it.name == "NemuiTV-$text.apk.sha256" } ?: return null
    if (apk.url.isBlank() || sha.url.isBlank()) return null
    return ReleaseInfo(version, text, dto.body.orEmpty(), apk.url, apk.size, sha.url)
}

/** [latest] only if strictly newer than [installed] (which may carry a suffix like "-debug"). */
fun newerRelease(installed: String, latest: ReleaseInfo?): ReleaseInfo? {
    val current = parseVersion(installed) ?: return null
    return latest?.takeIf { it.version > current }
}

/** Compares against the first whitespace-separated token, so `sha256sum` output works. */
fun sha256Matches(fileHex: String, shaFileText: String): Boolean {
    val expected = shaFileText.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
    return expected.isNotEmpty() && expected.equals(fileHex.trim(), ignoreCase = true)
}

/** Flattens release-note Markdown into plain text the TV UI can show. */
fun stripMarkdown(md: String): String =
    md.replace("\r\n", "\n")
        .lines()
        .joinToString("\n") { line ->
            line.replace(Regex("""^\s*#+\s*"""), "")
                .replace(Regex("""^(\s*)[-*]\s+"""), "$1• ")
        }
        .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

/** When to check for updates: shortly after launch, then every six hours. */
object UpdateSchedule {
    const val FIRST_DELAY_MS = 30_000L
    const val INTERVAL_MS = 6 * 3_600_000L

    fun due(nowMs: Long, lastSuccessMs: Long?): Boolean =
        lastSuccessMs == null || nowMs - lastSuccessMs >= INTERVAL_MS
}
