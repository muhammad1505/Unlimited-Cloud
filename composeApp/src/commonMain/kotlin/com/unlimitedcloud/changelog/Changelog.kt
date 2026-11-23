package com.unlimitedcloud.changelog

/**
 * One release, parsed from the `## <version> — <date>` sections of
 * `CHANGELOG.md`. The markdown file is bundled at build time by
 * `:composeApp:generateChangelog`, so editing the changelog never requires
 * touching Kotlin code.
 */
data class ChangelogRelease(
    val version: String,
    val date: String?,
    val sections: List<ChangelogSection>,
) {
    val bulletCount: Int
        get() = sections.sumOf { it.bullets.size }

    /** A release heading without any bullet is still listed but never popped up. */
    val isEmpty: Boolean
        get() = bulletCount == 0
}

data class ChangelogSection(
    val title: String,
    val bullets: List<String>,
)

object AppChangelog {
    val releases: List<ChangelogRelease> by lazy {
        parseChangelog(CHANGELOG_MARKDOWN_LINES)
    }

    fun release(version: String): ChangelogRelease? =
        releases.firstOrNull { it.version == version }
}

private const val DEFAULT_SECTION = "Perubahan"
private val VERSION_HEADING =
    Regex("^##\\s+(?:\\[)?(\\d+(?:\\.\\d+)+)\\]?(?:\\s*[—–-]\\s*(.+))?$")

/**
 * Minimal Markdown reader for the changelog shapes this project uses:
 * `## version — date`, `### section`, `- bullet`. HTML comments are skipped so
 * the bump script can leave editing instructions in the file.
 */
internal fun parseChangelog(lines: List<String>): List<ChangelogRelease> {
    val releases = mutableListOf<MutableRelease>()
    var current: MutableRelease? = null
    var sectionTitle: String? = null
    var inComment = false

    lines.forEach { rawLine ->
        val line = rawLine.trim()

        if (inComment) {
            if (line.contains("-->")) inComment = false
            return@forEach
        }
        if (line.startsWith("<!--")) {
            if (!line.contains("-->")) inComment = true
            return@forEach
        }

        val heading = VERSION_HEADING.matchEntire(line)
        if (heading != null) {
            val release = MutableRelease(
                version = heading.groupValues[1],
                date = heading.groupValues[2].trim().takeIf { it.isNotEmpty() },
            )
            releases += release
            current = release
            sectionTitle = null
            return@forEach
        }
        if (line.startsWith("## ")) {
            current = null
            sectionTitle = null
            return@forEach
        }

        val release = current ?: return@forEach

        if (line.startsWith("### ")) {
            sectionTitle = line.removePrefix("### ").trim().takeIf { it.isNotEmpty() }
            return@forEach
        }
        if (line.startsWith("- ")) {
            val bullet = line.removePrefix("- ").trim()
            if (bullet.isNotEmpty()) release.addBullet(sectionTitle ?: DEFAULT_SECTION, bullet)
        }
    }

    return releases.map(MutableRelease::toRelease)
}

private class MutableRelease(
    private val version: String,
    private val date: String?,
) {
    private val sections = mutableListOf<Pair<String, MutableList<String>>>()

    fun addBullet(section: String, bullet: String) {
        val bullets = sections.firstOrNull { it.first == section }?.second
            ?: mutableListOf<String>().also { sections += section to it }
        bullets += bullet
    }

    fun toRelease(): ChangelogRelease = ChangelogRelease(
        version = version,
        date = date,
        sections = sections.map { (title, bullets) -> ChangelogSection(title, bullets) },
    )
}
