package com.bilidownloader.mobile.engine

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object KuaishouParser {
    private const val MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    fun extractUrl(text: String): String? {
        val p = Pattern.compile("https?://(?:v\\.kuaishou\\.com/[a-zA-Z0-9_-]+|(?:[a-zA-Z0-9_-]+\\.)?kuaishou\\.com/[^\\s]+)")
        val m = p.matcher(text)
        return if (m.find()) m.group(0) else null
    }

    suspend fun parse(input: String): JsonObject = withContext(Dispatchers.IO) {
        val result = JsonObject()
        val targetUrl = extractUrl(input)
        if (targetUrl == null) {
            result.addProperty("success", false)
            result.addProperty("message", "未能识别出有效的快手分享链接")
            return@withContext result
        }

        try {
            var finalUrl = targetUrl
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", MOBILE_UA)
                .build()

            client.newCall(req).execute().use { resp ->
                finalUrl = resp.request.url.toString()
            }

            var html = ""
            val getReq = Request.Builder()
                .url(finalUrl)
                .header("User-Agent", MOBILE_UA)
                .header("Referer", "https://v.kuaishou.com/")
                .build()

            client.newCall(getReq).execute().use { resp ->
                html = resp.body?.string() ?: ""
            }

            var videoUrl: String? = null
            var title = "快手精选视频"
            var author = "快手创作者"
            var coverUrl = ""

            // Match window.pageData
            val dataP = Pattern.compile("window\\.pageData\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL)
            val dataM = dataP.matcher(html)
            if (dataM.find()) {
                val jsonStr = dataM.group(1)
                try {
                    val root = gson.fromJson(jsonStr, JsonObject::class.java)
                    val photo = root.getAsJsonObject("video")
                    if (photo != null) {
                        title = photo.get("caption")?.asString ?: title
                        author = photo.get("userName")?.asString ?: author
                        coverUrl = photo.get("poster")?.asString ?: ""
                        videoUrl = photo.get("srcNoMark")?.asString ?: photo.get("photoUrl")?.asString
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (videoUrl == null) {
                // Direct mp4 regex match
                val mp4P = Pattern.compile("https?://[^\"'\\s]+\\.mp4[^\"'\\s]*")
                val mp4M = mp4P.matcher(html)
                if (mp4M.find()) {
                    videoUrl = mp4M.group(0)
                }
            }

            if (videoUrl != null) {
                result.addProperty("success", true)
                result.addProperty("platform", "kuaishou")
                result.addProperty("title", title)
                result.addProperty("author", author)
                result.addProperty("pic", coverUrl)
                result.addProperty("direct_video_url", videoUrl)
            } else {
                result.addProperty("success", false)
                result.addProperty("message", "未能获取到快手无水印直链")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            result.addProperty("success", false)
            result.addProperty("message", "解析快手失败: ${e.message}")
        }
        result
    }
}
