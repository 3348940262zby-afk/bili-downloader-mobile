package com.bilidownloader.mobile.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object GalleryHelper {

    suspend fun saveVideoToGallery(
        context: Context,
        sourceFile: File,
        displayName: String
    ): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val cleanName = if (displayName.endsWith(".mp4", ignoreCase = true)) displayName else "$displayName.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, cleanName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/BiliDownloader")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        var uri: Uri? = null
        try {
            uri = resolver.insert(collection, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null)
                } catch (ignored: Exception) {}
            }
            return@withContext null
        }
        return@withContext uri
    }

    suspend fun saveAudioToGallery(
        context: Context,
        sourceFile: File,
        displayName: String
    ): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val cleanName = if (displayName.endsWith(".m4a", ignoreCase = true) || displayName.endsWith(".mp3", ignoreCase = true)) {
            displayName
        } else {
            "$displayName.m4a"
        }

        val mime = if (cleanName.endsWith(".mp3", ignoreCase = true)) "audio/mpeg" else "audio/mp4"

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, cleanName)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/BiliDownloader")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        var uri: Uri? = null
        try {
            uri = resolver.insert(collection, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null)
                } catch (ignored: Exception) {}
            }
            return@withContext null
        }
        return@withContext uri
    }
}
