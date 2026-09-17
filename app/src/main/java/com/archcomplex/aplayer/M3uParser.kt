package com.archcomplex.aplayer

data class RadioStation(val name: String, val url: String)

object M3uParser {
    fun parse(content: String): List<RadioStation> {
        val result = mutableListOf<RadioStation>()
        var pendingName: String? = null
        content.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.isBlank() || line.startsWith("#EXTM3U", ignoreCase = true) -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingName = line.substringAfter(',', "Онлайн-радио").trim().ifBlank { "Онлайн-радио" }
                }
                line.startsWith("#") -> Unit
                line.startsWith("http://", true) || line.startsWith("https://", true) -> {
                    result += RadioStation(pendingName ?: line, line)
                    pendingName = null
                }
            }
        }
        return result
    }
}
