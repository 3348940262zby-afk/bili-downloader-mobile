package com.bilidownloader.mobile.engine

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object DouyinParser {
    private const val MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()
    private var cachedTtwid: String? = null
    private var cachedTtwidTime: Long = 0

    fun extractUrl(text: String): String? {
        val p = Pattern.compile("https?://(?:v\\.douyin\\.com/[a-zA-Z0-9_-]+|(?:[a-zA-Z0-9_-]+\\.)?douyin\\.com/[^\\s]+|(?:www\\.)?iesdouyin\\.com/share/video/\\d+)")
        val m = p.matcher(text)
        return if (m.find()) m.group(0) else null
    }

    private suspend fun getTtwid(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedTtwid != null && (now - cachedTtwidTime < 3600 * 12 * 1000)) {
            return@withContext cachedTtwid
        }
        try {
            val url = "https://ttwid.bytedance.com/ttwid/union/register/"
            val jsonPayload = """{"region":"cn","aid":1768,"needFid":false,"service":"www.ixigua.com","migrate_info":{"ticket":"","source":"node"},"cbUrlProtocol":"https","union":true}"""
            val req = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("User-Agent", DESKTOP_UA)
                .post(jsonPayload.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val cookies = resp.headers("Set-Cookie")
                for (c in cookies) {
                    if (c.contains("ttwid=")) {
                        val valStr = c.substringAfter("ttwid=").substringBefore(";")
                        cachedTtwid = valStr
                        cachedTtwidTime = now
                        return@withContext valStr
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    suspend fun parse(input: String): JsonObject = withContext(Dispatchers.IO) {
        val result = JsonObject()
        val targetUrl = extractUrl(input)
        if (targetUrl == null) {
            result.addProperty("success", false)
            result.addProperty("message", "未能识别出有效的抖音分享链接 (如 https://v.douyin.com/...)")
            return@withContext result
        }

        try {
            // Step 1: Follow redirect
            var finalUrl = targetUrl
            val headReq = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", MOBILE_UA)
                .build()

            client.newCall(headReq).execute().use { resp ->
                finalUrl = resp.request.url.toString()
            }

            // Step 2: Extract item ID
            var itemId: String? = null
            val modalP = Pattern.compile("modal_id=(\\d{18,20})")
            val modalM = modalP.matcher(finalUrl)
            if (modalM.find()) {
                itemId = modalM.group(1)
            }
            if (itemId == null) {
                val videoP = Pattern.compile("/video/(\\d+)")
                val videoM = videoP.matcher(finalUrl)
                if (videoM.find()) {
                    itemId = videoM.group(1)
                }
            }
            if (itemId == null) {
                val digitP = Pattern.compile("(\\d{18,20})")
                val digitM = digitP.matcher(finalUrl)
                if (digitM.find()) {
                    itemId = digitM.group(1)
                }
            }

            if (itemId == null) {
                result.addProperty("success", false)
                result.addProperty("message", "未能从抖音链接中提取到视频 ID")
                return@withContext result
            }

            // Step 3: Fetch detail with TTWID
            val ttwid = getTtwid()
            val shareUrl = "https://www.iesdouyin.com/share/video/$itemId/"
            val shareReq = Request.Builder()
                .url(shareUrl)
                .header("User-Agent", MOBILE_UA)
                .header("Referer", "https://www.douyin.com/")
                .apply {
                    if (ttwid != null) header("Cookie", "ttwid=$ttwid")
                }
                .build()

            var html = ""
            client.newCall(shareReq).execute().use { resp ->
                html = resp.body?.string() ?: ""
            }

            // Find JSON in _ROUTER_DATA or RENDER_DATA
            var videoUrl: String? = null
            var title = "抖音视频_$itemId"
            var coverUrl = ""
            var author = "抖音用户"

            val routerP = Pattern.compile("<script[^>]*id=\"_ROUTER_DATA\"[^>]*>(.*?)</script>")
            val routerM = routerP.matcher(html)
            if (routerM.find()) {
                val rawJson = routerM.group(1)
                try {
                    val root = gson.fromJson(rawJson, JsonObject::class.java)
                    val loaderData = root.getAsJsonObject("loaderData")
                    val itemInfo = loaderData.entrySet().firstOrNull { it.key.contains("video_") }?.value?.asJsonObject
                        ?: loaderData.getAsJsonObject("video_(id)/page")
                    val videoData = itemInfo?.getAsJsonObject("videoInfoRes")
                        ?.getAsJsonArray("item_list")?.get(0)?.asJsonObject

                    if (videoData != null) {
                        title = videoData.get("desc")?.asString ?: title
                        author = videoData.getAsJsonObject("author")?.get("nickname")?.asString ?: author
                        coverUrl = videoData.getAsJsonObject("video")?.getAsJsonObject("cover")?.getAsJsonArray("url_list")?.get(0)?.asString ?: ""
                        val playList = videoData.getAsJsonObject("video")?.getAsJsonObject("play_addr")?.getAsJsonArray("url_list")
                        if (playList != null && playList.size() > 0) {
                            videoUrl = playList.get(0).asString.replace("playwm", "play")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (videoUrl == null) {
                // Regex fallback for playwm/play
                val fallbackP = Pattern.compile("https?://[^\"'\\s]+?(?:playwm|play)/[^\"'\\s]+")
                val fallbackM = fallbackP.matcher(html)
                if (fallbackM.find()) {
                    videoUrl = fallbackM.group(0).replace("playwm", "play")
                }
            }

            if (videoUrl != null) {
                result.addProperty("success", true)
                result.addProperty("platform", "douyin")
                result.addProperty("title", title)
                result.addProperty("author", author)
                result.addProperty("pic", coverUrl)
                result.addProperty("direct_video_url", videoUrl)
                result.addProperty("item_id", itemId)
            } else {
                result.addProperty("success", false)
                result.addProperty("message", "未能获取到抖音无水印直链，请稍后重试")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            result.addProperty("success", false)
            result.addProperty("message", "解析抖音失败: ${e.message}")
        }
        result
    }
}
