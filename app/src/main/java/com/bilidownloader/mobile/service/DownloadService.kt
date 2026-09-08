package com.bilidownloader.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bilidownloader.mobile.R
import com.bilidownloader.mobile.util.GalleryHelper
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DownloadTask(
    val id: String,
    val title: String,
    val videoUrl: String,
    val audioUrl: String? = null,
    val referer: String = "https://www.bilibili.com/",
    val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    var status: String = "pending", // pending, downloading, merging, saving, completed, failed
    var progress: Int = 0,
    var speed: String = "0 KB/s",
    var errorMessage: String? = null
)

class DownloadService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    private var progressListener: ((String, DownloadTask) -> Unit)? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    inner class LocalBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    fun setProgressListener(listener: (String, DownloadTask) -> Unit) {
        this.progressListener = listener
    }

    fun getTasks(): List<DownloadTask> = tasks.values.toList()

    fun addTask(task: DownloadTask) {
        tasks[task.id] = task
        startForeground(1001, buildNotification(task.title, "等待下载...", 0))
        serviceScope.launch {
            processTask(task)
        }
    }

    private suspend fun processTask(task: DownloadTask) {
        val cacheDir = externalCacheDir ?: cacheDir
        val safeTitle = task.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
        val tempVideoFile = File(cacheDir, "temp_${task.id}_video.m4s")
        val tempAudioFile = File(cacheDir, "temp_${task.id}_audio.m4s")
        val tempMergedFile = File(cacheDir, "merged_${task.id}.mp4")

        try {
            task.status = "downloading"
            notifyTaskUpdated(task)

            val hasAudioStream = !task.audioUrl.isNullOrEmpty()

            // 1. Download video track
            val videoSuccess = downloadFile(task.videoUrl, tempVideoFile, task.referer, task.userAgent) { bytesRead, totalBytes, speedStr ->
                val ratio = if (hasAudioStream) 0.8f else 1.0f
                val p = if (totalBytes > 0) ((bytesRead.toFloat() / totalBytes.toFloat()) * 100 * ratio).toInt() else 0
                task.progress = p
                task.speed = speedStr
                updateNotification(task.title, "下载视频流: $p% ($speedStr)", p)
                notifyTaskUpdated(task)
            }

            if (!videoSuccess) {
                task.status = "failed"
                task.errorMessage = "下载视频流失败"
                notifyTaskUpdated(task)
                return
            }

            // 2. Download audio track if separate (Bilibili DASH)
            if (hasAudioStream && task.audioUrl != null) {
                val audioSuccess = downloadFile(task.audioUrl, tempAudioFile, task.referer, task.userAgent) { bytesRead, totalBytes, speedStr ->
                    val p = 80 + if (totalBytes > 0) ((bytesRead.toFloat() / totalBytes.toFloat()) * 15).toInt() else 0
                    task.progress = p
                    task.speed = speedStr
                    updateNotification(task.title, "下载音频流: $p%", p)
                    notifyTaskUpdated(task)
                }

                if (!audioSuccess) {
                    task.status = "failed"
                    task.errorMessage = "下载音频流失败"
                    notifyTaskUpdated(task)
                    return
                }

                // 3. Muxing
                task.status = "merging"
                task.progress = 95
                updateNotification(task.title, "合成高清音视频中...", 95)
                notifyTaskUpdated(task)

                val muxSuccess = MediaMuxerHelper.muxVideoAndAudio(tempVideoFile, tempAudioFile, tempMergedFile)
                if (!muxSuccess) {
                    // Fallback to video only if muxing fails
                    tempVideoFile.copyTo(tempMergedFile, overwrite = true)
                }
            } else {
                tempVideoFile.copyTo(tempMergedFile, overwrite = true)
            }

            // 4. Save to System Gallery
            task.status = "saving"
            task.progress = 98
            updateNotification(task.title, "写入手机系统相册...", 98)
            notifyTaskUpdated(task)

            val isAudioOnly = !hasAudioStream && (task.title.contains("音频") || task.title.contains("audio", ignoreCase = true) || task.videoUrl.contains("audio"))
            val savedUri = if (isAudioOnly) {
                GalleryHelper.saveAudioToGallery(this@DownloadService, tempMergedFile, "$safeTitle.m4a")
            } else {
                GalleryHelper.saveVideoToGallery(this@DownloadService, tempMergedFile, "$safeTitle.mp4")
            }
            if (savedUri != null) {
                task.status = "completed"
                task.progress = 100
                val msg = if (isAudioOnly) "下载完成，已存入音乐库！" else "下载完成，已存入相册！"
                updateNotification(task.title, msg, 100)
                notifyTaskUpdated(task)
            } else {
                task.status = "failed"
                task.errorMessage = "写入系统媒体库失败"
                notifyTaskUpdated(task)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            task.status = "failed"
            task.errorMessage = e.message ?: "未知下载错误"
            notifyTaskUpdated(task)
        } finally {
            tempVideoFile.delete()
            tempAudioFile.delete()
            tempMergedFile.delete()
        }
    }

    private fun downloadFile(
        url: String,
        targetFile: File,
        referer: String,
        userAgent: String,
        onProgress: (Long, Long, String) -> Unit
    ): Boolean {
        val req = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("User-Agent", userAgent)
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                val totalBytes = body.contentLength()

                if (targetFile.exists()) targetFile.delete()
                targetFile.createNewFile()

                var bytesCopied: Long = 0
                val buffer = ByteArray(64 * 1024)
                var lastTime = System.currentTimeMillis()
                var bytesSinceLast = 0L

                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        var read = input.read(buffer)
                        while (read >= 0) {
                            output.write(buffer, 0, read)
                            bytesCopied += read
                            bytesSinceLast += read

                            val now = System.currentTimeMillis()
                            if (now - lastTime >= 500) {
                                val speedBps = (bytesSinceLast * 1000) / (now - lastTime)
                                val speedStr = if (speedBps > 1024 * 1024) {
                                    String.format("%.1f MB/s", speedBps / (1024f * 1024f))
                                } else {
                                    String.format("%d KB/s", speedBps / 1024)
                                }
                                onProgress(bytesCopied, totalBytes, speedStr)
                                lastTime = now
                                bytesSinceLast = 0
                            }
                            read = input.read(buffer)
                        }
                        output.flush()
                    }
                }
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun notifyTaskUpdated(task: DownloadTask) {
        progressListener?.invoke(task.id, task)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "download_channel",
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.download_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, "download_channel")
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setProgress(100, progress, false)
            .setOngoing(progress in 1..99)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, content: String, progress: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1001, buildNotification(title, content, progress))
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
