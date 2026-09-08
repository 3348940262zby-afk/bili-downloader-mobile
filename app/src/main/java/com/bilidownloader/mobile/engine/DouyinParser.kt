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

    private val noRedirectClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val gson = Gson()
    private var cachedTtwid: String? = null
    private var cachedTtwidTime: Long = 0

    fun extractUrl(text: String): String? {
        val p = Pattern.compile("https?://(?:v\\.douyin\\.com/[a-zA-Z0-9_/-]+|(?:[a-zA-Z0-9_.-]+\\.)?(?:douyin|iesdouyin)\\.com/[a-zA-Z0-9_.~:/?#\\[\\]@!$&'()*+,;=%-]+)")
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
            result.addProperty("message", "未能识别出有效的抖音分享链接 (如 https://v.douyin.com/... 或 https://www.iesdouyin.com/share/video/...)")
            return@withContext result
        }

        try {
            // Step 1: Extract item ID directly if already in targetUrl
            var itemId: String? = null
            val videoP = Pattern.compile("/video/(\\d+)")
            val noteP = Pattern.compile("/note/(\\d+)")
            val modalP = Pattern.compile("modal_id=(\\d+)")
            val digitP = Pattern.compile("(\\d{18,20})")

            var vm = videoP.matcher(targetUrl)
            if (vm.find()) itemId = vm.group(1)
            if (itemId == null) {
                val nm = noteP.matcher(targetUrl)
                if (nm.find()) itemId = nm.group(1)
            }
            if (itemId == null) {
                val mm = modalP.matcher(targetUrl)
                if (mm.find()) itemId = mm.group(1)
            }

            // If not found in targetUrl (e.g. shortlink https://v.douyin.com/xxxx), follow redirect
            var finalUrl: String = targetUrl
            if (itemId == null) {
                val headReq = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", MOBILE_UA)
                    .build()

                try {
                    noRedirectClient.newCall(headReq).execute().use { resp ->
                        val loc = resp.header("Location")
                        if (!loc.isNullOrBlank()) {
                            val lm = videoP.matcher(loc)
                            if (lm.find()) itemId = lm.group(1)
                            if (itemId == null) {
                                val nm = noteP.matcher(loc)
                                if (nm.find()) itemId = nm.group(1)
                            }
                            if (itemId == null) {
                                val mm = modalP.matcher(loc)
                                if (mm.find()) itemId = mm.group(1)
                            }
                            if (itemId == null) {
                                val dm = digitP.matcher(loc)
                                if (dm.find()) itemId = dm.group(1)
                            }
                        }
                    }
                } catch (ignored: Exception) {}

                if (itemId == null) {
                    client.newCall(headReq).execute().use { resp ->
                        finalUrl = resp.request.url.toString()
                    }

                    vm = videoP.matcher(finalUrl)
                    if (vm.find()) itemId = vm.group(1)
                    if (itemId == null) {
                        val nm = noteP.matcher(finalUrl)
                        if (nm.find()) itemId = nm.group(1)
                    }
                    if (itemId == null) {
                        val mm = modalP.matcher(finalUrl)
                        if (mm.find()) itemId = mm.group(1)
                    }
                    if (itemId == null) {
                        val dm = digitP.matcher(finalUrl)
                        if (dm.find()) itemId = dm.group(1)
                    }
                }
            }

            if (itemId == null) {
                result.addProperty("success", false)
                result.addProperty("message", "未能从抖音链接中提取到视频 ID")
                return@withContext result
            }

            // Step 2: Fetch detail with TTWID
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

            var videoUrl: String? = null
            var title = "抖音视频_$itemId"
            var coverUrl = ""
            var author = "抖音用户"

            // Step 3: Extract JSON from window._ROUTER_DATA or script tags
            try {
                var rawJson: String? = null
                val routerIdx = html.indexOf("window._ROUTER_DATA")
                if (routerIdx != -1) {
                    val scriptEndIdx = html.indexOf("</script>", routerIdx)
                    if (scriptEndIdx != -1) {
                        val assignIdx = html.indexOf("=", routerIdx)
                        if (assignIdx in routerIdx until scriptEndIdx) {
                            var snippet = html.substring(assignIdx + 1, scriptEndIdx).trim()
                            if (snippet.endsWith(";")) {
                                snippet = snippet.substring(0, snippet.length - 1).trim()
                            }
                            rawJson = snippet
                        }
                    }
                }

                if (rawJson == null) {
                    val routerP = Pattern.compile("<script[^>]*id=\"_ROUTER_DATA\"[^>]*>(.*?)</script>")
                    val routerM = routerP.matcher(html)
                    if (routerM.find()) {
                        rawJson = routerM.group(1).trim()
                    }
                }

                if (!rawJson.isNullOrBlank()) {
                    val root = gson.fromJson(rawJson, JsonObject::class.java)
                    val loaderData = root?.getAsJsonObject("loaderData")
                    if (loaderData != null) {
                        // Find entry that actually contains videoInfoRes (prevent picking null video_layout)
                        var videoData: JsonObject? = null
                        for (entry in loaderData.entrySet()) {
                            if (entry.value.isJsonObject) {
                                val itemObj = entry.value.asJsonObject
                                if (itemObj.has("videoInfoRes")) {
                                    val vir = itemObj.getAsJsonObject("videoInfoRes")
                                    val itemList = vir.getAsJsonArray("item_list")
                                    if (itemList != null && itemList.size() > 0 && itemList.get(0).isJsonObject) {
                                        videoData = itemList.get(0).asJsonObject
                                        break
                                    }
                                }
                            }
                        }

                        if (videoData != null) {
                            title = videoData.get("desc")?.asString ?: title
                            val authorObj = videoData.getAsJsonObject("author")
                            if (authorObj != null && authorObj.has("nickname")) {
                                author = authorObj.get("nickname").asString
                            }

                            val videoObj = videoData.getAsJsonObject("video")
                            if (videoObj != null) {
                                val coverList = videoObj.getAsJsonObject("cover")?.getAsJsonArray("url_list")
                                if (coverList != null && coverList.size() > 0) {
                                    coverUrl = coverList.get(0).asString
                                }
                                val playList = videoObj.getAsJsonObject("play_addr")?.getAsJsonArray("url_list")
                                if (playList != null && playList.size() > 0) {
                                    videoUrl = playList.get(0).asString.replace("playwm", "play")
                                }
                            }

                            // Check images/images_list for note/photo gallery posts
                            if (videoUrl == null && videoData.has("images")) {
                                val imagesArr = videoData.getAsJsonArray("images")
                                if (imagesArr != null && imagesArr.size() > 0 && imagesArr.get(0).isJsonObject) {
                                    val firstImg = imagesArr.get(0).asJsonObject
                                    val urlList = firstImg.getAsJsonArray("url_list")
                                    if (urlList != null && urlList.size() > 0) {
                                        coverUrl = urlList.get(0).asString
                                        videoUrl = urlList.get(0).asString
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Step 4: Fallback extraction on unescaped HTML
            if (videoUrl == null) {
                val unescaped = html.replace("\\u002F", "/").replace("\\/", "/")
                val fallbackP = Pattern.compile("https?://[^\"'\\s<>]+?(?:playwm|play)/[^\"'\\s<>]+")
                val fallbackM = fallbackP.matcher(unescaped)
                if (fallbackM.find()) {
                    videoUrl = fallbackM.group(0).replace("playwm", "play")
                }
            }

            if (title == "抖音视频_$itemId") {
                val titleP = Pattern.compile("<title>(.*?)</title>")
                val titleM = titleP.matcher(html)
                if (titleM.find()) {
                    val t = titleM.group(1).trim()
                    if (t.isNotEmpty()) title = t
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
                result.addProperty("message", "未能获取到抖音无水印直链，请检查链接或稍后重试")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            result.addProperty("success", false)
            result.addProperty("message", "解析抖音失败: ${e.message}")
        }
        result
    }
}
