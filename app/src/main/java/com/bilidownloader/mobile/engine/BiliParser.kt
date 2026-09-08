package com.bilidownloader.mobile.engine

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object BiliParser {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    const val REFERER = "https://www.bilibili.com/"

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

    private suspend fun resolveAidToBvid(aid: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.bilibili.com/x/web-interface/view?aid=$aid")
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val json = gson.fromJson(body, JsonObject::class.java)
                if (json.get("code")?.asInt == 0) {
                    return@withContext json.getAsJsonObject("data")?.get("bvid")?.asString
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    private suspend fun resolveEpToBvid(epId: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.bilibili.com/pgc/view/web/season?ep_id=$epId")
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val json = gson.fromJson(body, JsonObject::class.java)
                if (json.get("code")?.asInt == 0) {
                    val result = json.getAsJsonObject("result")
                    val eps = result?.getAsJsonArray("episodes")
                    if (eps != null && eps.size() > 0) {
                        for (ep in eps) {
                            if (ep.isJsonObject) {
                                val epObj = ep.asJsonObject
                                if (epObj.get("id")?.asString == epId) {
                                    val bvid = epObj.get("bvid")?.asString
                                    if (!bvid.isNullOrEmpty()) return@withContext bvid
                                }
                            }
                        }
                        val firstEp = eps.get(0).asJsonObject
                        return@withContext firstEp.get("bvid")?.asString
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    private suspend fun resolveSeasonToBvid(seasonId: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.bilibili.com/pgc/view/web/season?season_id=$seasonId")
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val json = gson.fromJson(body, JsonObject::class.java)
                if (json.get("code")?.asInt == 0) {
                    val result = json.getAsJsonObject("result")
                    val eps = result?.getAsJsonArray("episodes")
                    if (eps != null && eps.size() > 0) {
                        val firstEp = eps.get(0).asJsonObject
                        return@withContext firstEp.get("bvid")?.asString
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    suspend fun resolveBvid(input: String): String? = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        val bvPattern = Pattern.compile("(?i)(BV[a-zA-Z0-9]{10})")
        val avPattern = Pattern.compile("(?i)\\bav(\\d+)\\b")
        val epPattern = Pattern.compile("(?i)\\bep(\\d+)\\b")
        val ssPattern = Pattern.compile("(?i)\\bss(\\d+)\\b")

        val matcher = bvPattern.matcher(trimmed)
        if (matcher.find()) {
            return@withContext matcher.group(1)
        }

        // Handle b23.tv / bili2233.cn shortlink (e.g. https://b23.tv/nNqA5nY)
        val shortLinkPattern = Pattern.compile("(?i)(?:https?://)?(?:b23\\.tv|bili2233\\.cn)/([a-zA-Z0-9]+)")
        val shortMatcher = shortLinkPattern.matcher(trimmed)
        if (shortMatcher.find()) {
            val code = shortMatcher.group(1)
            var currentUrl = "https://b23.tv/$code"
            var redirectCount = 0
            while (redirectCount < 4) {
                try {
                    val req = Request.Builder()
                        .url(currentUrl)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", REFERER)
                        .build()

                    val resp = noRedirectClient.newCall(req).execute()
                    val loc = resp.header("Location")
                    resp.close()

                    if (!loc.isNullOrBlank()) {
                        val m2 = bvPattern.matcher(loc)
                        if (m2.find()) {
                            return@withContext m2.group(1)
                        }
                        val mAv = avPattern.matcher(loc)
                        if (mAv.find()) {
                            val bvid = resolveAidToBvid(mAv.group(1))
                            if (bvid != null) return@withContext bvid
                        }
                        val mEp = epPattern.matcher(loc)
                        if (mEp.find()) {
                            val bvid = resolveEpToBvid(mEp.group(1))
                            if (bvid != null) return@withContext bvid
                        }
                        val mSs = ssPattern.matcher(loc)
                        if (mSs.find()) {
                            val bvid = resolveSeasonToBvid(mSs.group(1))
                            if (bvid != null) return@withContext bvid
                        }
                        currentUrl = if (loc.startsWith("http")) loc else "https://$loc"
                        redirectCount++
                    } else {
                        // Not a redirect, try normal client as fallback
                        client.newCall(req).execute().use { fullResp ->
                            val finalUrl = fullResp.request.url.toString()
                            val m3 = bvPattern.matcher(finalUrl)
                            if (m3.find()) {
                                return@withContext m3.group(1)
                            }
                            val mAv = avPattern.matcher(finalUrl)
                            if (mAv.find()) {
                                val bvid = resolveAidToBvid(mAv.group(1))
                                if (bvid != null) return@withContext bvid
                            }
                            val mEp = epPattern.matcher(finalUrl)
                            if (mEp.find()) {
                                val bvid = resolveEpToBvid(mEp.group(1))
                                if (bvid != null) return@withContext bvid
                            }
                        }
                        break
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    break
                }
            }
        }

        // Handle av id (e.g. av170001 or av 123456)
        val avMatcher = avPattern.matcher(trimmed)
        if (avMatcher.find()) {
            val aid = avMatcher.group(1)
            val bvid = resolveAidToBvid(aid)
            if (bvid != null) return@withContext bvid
        }

        // Handle ep id (Bangumi Episode)
        val epMatcher = epPattern.matcher(trimmed)
        if (epMatcher.find()) {
            val epId = epMatcher.group(1)
            val bvid = resolveEpToBvid(epId)
            if (bvid != null) return@withContext bvid
        }

        // Handle ss id (Bangumi Season)
        val ssMatcher = ssPattern.matcher(trimmed)
        if (ssMatcher.find()) {
            val ssId = ssMatcher.group(1)
            val bvid = resolveSeasonToBvid(ssId)
            if (bvid != null) return@withContext bvid
        }

        null
    }

    suspend fun getWbiKeys(cookies: String = ""): Pair<String, String> = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder()
                .url("https://api.bilibili.com/x/web-interface/nav")
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
            if (cookies.isNotEmpty()) {
                reqBuilder.header("Cookie", cookies)
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val json = gson.fromJson(body, JsonObject::class.java)
                val data = json.getAsJsonObject("data")
                val wbiImg = data.getAsJsonObject("wbi_img")
                val imgUrl = wbiImg.get("img_url").asString
                val subUrl = wbiImg.get("sub_url").asString

                val imgKey = imgUrl.substringAfterLast("/").substringBefore(".")
                val subKey = subUrl.substringAfterLast("/").substringBefore(".")
                return@withContext Pair(imgKey, subKey)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Pair("653657f524a14777893f6e93db5283b5", "869f6343e03c44ce9a4c4c873aa80227")
        }
    }

    suspend fun parseVideo(bvid: String, cookies: String = ""): JsonObject = withContext(Dispatchers.IO) {
        val result = JsonObject()
        try {
            val viewUrl = "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
            val req = Request.Builder()
                .url(viewUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .apply { if (cookies.isNotEmpty()) header("Cookie", cookies) }
                .build()

            val viewResp = client.newCall(req).execute()
            val viewBody = viewResp.body?.string() ?: ""
            val viewJson = gson.fromJson(viewBody, JsonObject::class.java)

            if (viewJson.get("code")?.asInt != 0) {
                result.addProperty("success", false)
                result.addProperty("message", viewJson.get("message")?.asString ?: "获取视频详情失败")
                return@withContext result
            }

            val data = viewJson.getAsJsonObject("data")
            val title = data.get("title").asString
            val pic = data.get("pic").asString
            val cid = data.get("cid").asLong
            val duration = data.get("duration").asLong
            val owner = data.getAsJsonObject("owner")
            val author = owner.get("name").asString

            val (imgKey, subKey) = getWbiKeys(cookies)

            val params = mutableMapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                "qn" to "127",
                "fnval" to "4048", // Request DASH (4K, 1080P60, High Bitrate)
                "fourk" to "1"
            )
            WbiSigner.encWbi(params, imgKey, subKey)

            val playQuery = params.map { "${it.key}=${it.value}" }.joinToString("&")
            val playUrl = "https://api.bilibili.com/x/player/wbi/playurl?$playQuery"

            val playReq = Request.Builder()
                .url(playUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .apply { if (cookies.isNotEmpty()) header("Cookie", cookies) }
                .build()

            val playResp = client.newCall(playReq).execute()
            val playBody = playResp.body?.string() ?: ""
            val playJson = gson.fromJson(playBody, JsonObject::class.java)
            val playData = playJson.getAsJsonObject("data")

            result.addProperty("success", true)
            result.addProperty("platform", "bilibili")
            result.addProperty("bvid", bvid)
            result.addProperty("cid", cid)
            result.addProperty("title", title)
            result.addProperty("pic", pic)
            result.addProperty("author", author)
            result.addProperty("duration", duration)
            result.add("play_data", playData)
            result.add("pages", data.getAsJsonArray("pages"))

        } catch (e: Exception) {
            e.printStackTrace()
            result.addProperty("success", false)
            result.addProperty("message", "解析错误: ${e.message}")
        }
        result
    }

    suspend fun generateQrCode(): JsonObject = withContext(Dispatchers.IO) {
        val result = JsonObject()
        try {
            val req = Request.Builder()
                .url("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                return@withContext gson.fromJson(body, JsonObject::class.java)
            }
        } catch (e: Exception) {
            result.addProperty("code", -1)
            result.addProperty("message", e.message)
            return@withContext result
        }
    }

    suspend fun pollQrCode(qrcodeKey: String): JsonObject = withContext(Dispatchers.IO) {
        val result = JsonObject()
        try {
            val url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$qrcodeKey"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val json = gson.fromJson(body, JsonObject::class.java)
                val setCookies = resp.headers("Set-Cookie")
                val cookieMap = mutableMapOf<String, String>()
                for (c in setCookies) {
                    val pair = c.substringBefore(";").split("=")
                    if (pair.size == 2) {
                        cookieMap[pair[0].trim()] = pair[1].trim()
                    }
                }
                val cookiesStr = cookieMap.map { "${it.key}=${it.value}" }.joinToString("; ")
                if (cookiesStr.isNotEmpty()) {
                    json.addProperty("cookies", cookiesStr)
                }
                return@withContext json
            }
        } catch (e: Exception) {
            result.addProperty("code", -1)
            result.addProperty("message", e.message)
            return@withContext result
        }
    }
}
