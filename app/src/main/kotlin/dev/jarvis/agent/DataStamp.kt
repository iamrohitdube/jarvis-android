package dev.jarvis.agent

/**
 * Where a reply's connector data came from and how old it is, for the "last updated" label.
 * Stored compactly on the message row (see [encode]).
 */
data class DataStamp(val source: String, val tool: String, val updatedAt: Long, val cached: Boolean) {
    companion object {
        fun encode(stamps: List<DataStamp>): String? =
            stamps.takeIf { it.isNotEmpty() }?.joinToString(";") { "${it.source.clean()}|${it.tool.clean()}|${it.updatedAt}|${if (it.cached) "cached" else "live"}" }

        fun decode(raw: String?): List<DataStamp> = raw.orEmpty().split(";").mapNotNull { part ->
            val f = part.split("|")
            if (f.size != 4) return@mapNotNull null
            DataStamp(f[0], f[1], f[2].toLongOrNull() ?: return@mapNotNull null, f[3] == "cached")
        }

        private fun String.clean() = replace("|", "").replace(";", "")
    }
}
