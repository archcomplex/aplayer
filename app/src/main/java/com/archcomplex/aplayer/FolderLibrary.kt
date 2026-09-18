package com.archcomplex.aplayer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object FolderLibrary {
    private const val PREFS = "folder_library"
    private const val FOLDERS = "folders"
    fun getFolders(context: Context): List<String> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(FOLDERS, emptySet()).orEmpty().toList().sorted()
    fun addFolder(context: Context, uri: Uri) { val set=getFolders(context).toMutableSet(); set.add(uri.toString()); context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(FOLDERS,set).apply() }
    fun removeFolder(context: Context, uri: String) { val set=getFolders(context).toMutableSet(); set.remove(uri); context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(FOLDERS,set).apply() }
    fun scan(context: Context): List<Track> = getFolders(context).flatMap { root -> DocumentFile.fromTreeUri(context,Uri.parse(root))?.let { scanDirectory(it) }.orEmpty() }.distinctBy { it.id }.sortedBy { it.title.lowercase() }
    private fun scanDirectory(dir: DocumentFile): List<Track> = dir.listFiles().flatMap { file -> when { file.isDirectory -> scanDirectory(file); file.isFile && isAudio(file.name,file.type) -> listOf(Track(file.uri.toString(),file.name?.substringBeforeLast('.')?.ifBlank{"Без названия"} ?: "Без названия","Неизвестный исполнитель","Неизвестный альбом",file.uri.toString(),size=file.length())); else -> emptyList() } }
    private fun isAudio(name:String?,type:String?)=type?.startsWith("audio/")==true || name.orEmpty().lowercase().let { it.endsWith(".mp3")||it.endsWith(".m4a")||it.endsWith(".aac")||it.endsWith(".wav")||it.endsWith(".ogg")||it.endsWith(".flac") }
}
