package com.archcomplex.aplayer

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

object FolderLibrary {
    private const val PREFS = "folder_library"
    private const val FOLDERS = "folders"

    fun getFolders(context: Context): List<String> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(FOLDERS, emptySet()).orEmpty().toList().sorted()

    fun addFolder(context: Context, uri: Uri) {
        val folders = getFolders(context).toMutableSet()
        folders += uri.toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(FOLDERS, folders).apply()
    }

    fun removeFolder(context: Context, uri: String) {
        val folders = getFolders(context).toMutableSet()
        folders.remove(uri)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(FOLDERS, folders).apply()
    }

    fun scan(context: Context): List<Track> = getFolders(context).flatMap { rootUri ->
        val root = DocumentFile.fromTreeUri(context, Uri.parse(rootUri)) ?: return@flatMap emptyList()
        scanDirectory(context, root)
    }.distinctBy { it.id }.sortedBy { it.title.lowercase() }

    private fun scanDirectory(context: Context, directory: DocumentFile): List<Track> {
        return directory.listFiles().flatMap { file ->
            when {
                file.isDirectory -> scanDirectory(context, file)
                file.isFile && isAudio(file.name, file.type) -> listOf(
                    Track(
                        id = file.uri.toString(),
                        title = file.name?.substringBeforeLast('.')?.ifBlank { "Без названия" } ?: "Без названия",
                        artist = "Неизвестный исполнитель",
                        album = "Неизвестный альбом",
                        uri = file.uri.toString()
                    )
                )
                else -> emptyList()
            }
        }
    }

    private fun isAudio(name: String?, mimeType: String?): Boolean {
        val lower = name.orEmpty().lowercase()
        return mimeType == "audio/mpeg" || mimeType == "audio/mp4" ||
            lower.endsWith(".mp3") || lower.endsWith(".m4a")
    }
}
