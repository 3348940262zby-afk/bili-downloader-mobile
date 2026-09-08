package com.bilidownloader.mobile.engine

import com.bilidownloader.mobile.util.*
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

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val noRedirectClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val gson = Gson()
    private var cachedBuvid: String? = null
    private var cachedWbiKeys: Pair<String, String>? = null
    private var cachedWbiTime: Long = 0

    suspend fun ensureBuvid(): String = withContext(Dispatchers.IO) {
        cachedBuvid?.let { return@withContext it }
        try {
            val req = Request.Builder()
                .url("https://api.bilibili.com/x/frontend/finger/spi")
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                val json = body.parseAsJsonObject()
                val data = json.obj("data")
                val b3 = data.str("b_3")
                val b4 = data.str("b_4")
                if (!b3.isNullOrEmpty()) {
                    val cookieStr = if (!b4.isNullOrEmpty()) "buvid3=$b3; buvid4=$b4" else "buvid3=$b3"
                    cachedBuvid = cookieStr
                    return@withContext cookieStr
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val fallback = "buvid3=8E3BC943-63E9-D2BD-0B37-9D802D08A1D464671infoc; b_nut=1788854464"
        cachedBuvid = fallback
        fallback
    }

    private suspend fun resolveAidToBvid(aid: String): String? = withContext(Dispatchers.IO) {
        try {
            val buvid = ensureBuvid()
            val req = Request.Builder()
                .url("https://api.bilibili.com/x/web-interface/view?aid=$aid")
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Cookie", buvid)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                val json = body.parseAsJsonObject()
                if (json.int("code", -1) == 0) {
                    return@withContext json.obj("data").str("bvid")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    private suspend fun resolveEpToBvid(epId: String): String? = withContext(Dispatchers.IO) {
        try {
            val buvid = ensureBuvid()
            val req = Request.Builder()
                .url("https://api.bilibili.com/pgc/view/web/season?ep_id=$epId")
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Cookie", buvid)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                val json = body.parseAsJsonObject()
                if (json.int("code", -1) == 0) {
                    val result = json.obj("result")
                    val eps = result.arr("episodes")
                    if (eps != null && eps.size() > 0) {
                        for (ep in eps) {
                            val epObj = ep.asSafeObject()
                            if (epObj.str("id") == epId) {
                                val bvid = epObj.str("bvid")
                                if (!bvid.isNullOrEmpty()) return@withContext bvid
                            }
                        }
                        return@withContext eps.get(0).asSafeObject().str("bvid")
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
            val buvid = ensureBuvid()
            val req = Request.Builder()
                .url("https://api.bilibili.com/pgc/view/web/season?season_id=$seasonId")
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Cookie", buvid)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                val json = body.parseAsJsonObject()
                if (json.int("code", -1) == 0) {
                    val result = json.obj("result")
                    val eps = result.arr("episodes")
                    if (eps != null && eps.size() > 0) {
                        return@withContext eps.get(0).asSafeObject().str("bvid")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    data class BiliTarget(val bvid: String, val page: Int = 1, val cid: Long? = null)

    suspend fun resolveTarget(input: String): BiliTarget? = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        val bvPattern = Pattern.compile("(?i)(BV[a-zA-Z0-9]{10})")
        val avPattern = Pattern.compile("(?i)\\bav(\\d+)\\b")
        val epPattern = Pattern.compile("(?i)\\bep(\\d+)\\b")
        val ssPattern = Pattern.compile("(?i)\\bss(\\d+)\\b")
        val pagePattern = Pattern.compile("(?i)[?&]p=(\\d+)")
        val cidPattern = Pattern.compile("(?i)[?&]cid=(\\d+)")

        fun extractPage(s: String): Int {
            val m = pagePattern.matcher(s)
            return if (m.find()) m.group(1).toIntOrNull() ?: 1 else 1
        }

        fun extractCid(s: String): Long? {
            val m = cidPattern.matcher(s)
            return if (m.find()) m.group(1).toLongOrNull() else null
        }

        val matcher = bvPattern.matcher(trimmed)
        if (matcher.find()) {
            return@withContext BiliTarget(matcher.group(1), extractPage(trimmed), extractCid(trimmed))
        }

        // Handle b23.tv / bili2233.cn shortlinks (e.g. https://b23.tv/qSBLNyf)
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
                            return@withContext BiliTarget(m2.group(1), extractPage(loc), extractCid(loc))
                        }
                        val mAv = avPattern.matcher(loc)
                        if (mAv.find()) {
                            val bvid = resolveAidToBvid(mAv.group(1))
                            if (bvid != null) return@withContext BiliTarget(bvid, extractPage(loc), extractCid(loc))
                        }
                        val mEp = epPattern.matcher(loc)
                        if (mEp.find()) {
                            val bvid = resolveEpToBvid(mEp.group(1))
                            if (bvid != null) return@withContext BiliTarget(bvid, extractPage(loc), extractCid(loc))
                        }
                        val mSs = ssPattern.matcher(loc)
                        if (mSs.find()) {
                            val bvid = resolveSeasonToBvid(mSs.group(1))
                            if (bvid != null) return@withContext BiliTarget(bvid, extractPage(loc), extractCid(loc))
                        }
                        currentUrl = if (loc.startsWith("http")) loc else "https://$loc"
                        redirectCount++
                    } else {
                        // Not a 302, follow with normal redirecting client
                        client.newCall(req).execute().use { fullResp ->
                            val finalUrl = fullResp.request.url.toString()
                            val m3 = bvPattern.matcher(finalUrl)
                            if (m3.find()) {
                                return@withContext BiliTarget(m3.group(1), extractPage(finalUrl), extractCid(finalUrl))
                            }
                            val mAv = avPattern.matcher(finalUrl)
                            if (mAv.find()) {
                                val bvid = resolveAidToBvid(mAv.group(1))
                                if (bvid != null) return@withContext BiliTarget(bvid, extractPage(finalUrl), extractCid(finalUrl))
                            }
                            val mEp = epPattern.matcher(finalUrl)
                            if (mEp.find()) {
                                val bvid = resolveEpToBvid(mEp.group(1))
                                if (bvid != null) return@withContext BiliTarget(bvid, extractPage(finalUrl), extractCid(finalUrl))
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

        // Direct av ID
        val avMatcher = avPattern.matcher(trimmed)
        if (avMatcher.find()) {
            val aid = avMatcher.group(1)
            val bvid = resolveAidToBvid(aid)
            if (bvid != null) return@withContext BiliTarget(bvid, extractPage(trimmed), extractCid(trimmed))
        }

        // Direct ep ID
        val epMatcher = epPattern.matcher(trimmed)
        if (epMatcher.find()) {
            val epId = epMatcher.group(1)
            val bvid = resolveEpToBvid(epId)
            if (bvid != null) return@withContext BiliTarget(bvid, extractPage(trimmed), extractCid(trimmed))
        }

        // Direct ss ID
        val ssMatcher = ssPattern.matcher(trimmed)
        if (ssMatcher.find()) {
            val ssId = ssMatcher.group(1)
            val bvid = resolveSeasonToBvid(ssId)
            if (bvid != null) return@withContext BiliTarget(bvid, extractPage(trimmed), extractCid(trimmed))
        }

        null
    }

    suspend fun resolveBvid(input: String): String? {
        return resolveTarget(input)?.bvid
    }

    suspend fun getWbiKeys(cookies: String = ""): Pair<String, String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedWbiKeys != null && (now - cachedWbiTime < 3600 * 4 * 1000)) {
            return@withContext cachedWbiKeys!!
        }

        try {
            val buvid = ensureBuvid()
            val finalCookie = if (cookies.isNotEmpty()) "$cookies; $buvid" else buvid
            val reqBuilder = Request.Builder()
                .url("https://api.bilibili.com/x/web-interface/nav")
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Cookie", finalCookie)

            client.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string()
                val json = body.parseAsJsonObject()
                val data = json.obj("data")
                val wbiImg = data.obj("wbi_img")
                val imgUrl = wbiImg.str("img_url")
                val subUrl = wbiImg.str("sub_url")

                if (!imgUrl.isNullOrEmpty() && !subUrl.isNullOrEmpty()) {
                    val imgKey = imgUrl.substringAfterLast("/").substringBefore(".")
                    val subKey = subUrl.substringAfterLast("/").substringBefore(".")
                    val pair = Pair(imgKey, subKey)
                    cachedWbiKeys = pair
                    cachedWbiTime = now
                    return@withContext pair
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val fallback = Pair("7cd084941338484aae1ad9425b84077c", "4932caff0ff746eab6f01bf08b70ac45")
        cachedWbiKeys = fallback
        fallback
    }

    suspend fun parseVideo(
        bvid: String,
        cookies: String = "",
        targetPage: Int = 1,
        targetCid: Long? = null
    ): JsonObject = withContext(Dispatchers.IO) {
        val result = JsonObject()
        try {
            val buvid = ensureBuvid()
            val finalCookie = if (cookies.isNotEmpty()) "$cookies; $buvid" else buvid

            val viewUrl = "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
            val req = Request.Builder()
                .url(viewUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Cookie", finalCookie)
                .build()

            var viewBody = ""
            var viewCode = 200
            client.newCall(req).execute().use { viewResp ->
                viewCode = viewResp.code
                viewBody = viewResp.body?.string() ?: ""
                if (!viewResp.isSuccessful || viewBody.trimStart().startsWith("<")) {
                    result.addProperty("success", false)
                    result.addProperty(
                        "message",
                        if (viewResp.code == 412) "B站触发安全拦截 (HTTP 412)，建议先点击右上角登录B站账号" 
                        else "获取视频详情失败: HTTP ${viewResp.code}"
                    )
                    return@withContext result
                }
            }

            val viewJson = viewBody.parseAsJsonObject()
            if (viewJson == null) {
                result.addProperty("success", false)
                result.addProperty("message", "解析视频详情失败: 响应非合法 JSON 格式")
                return@withContext result
            }

            val code = viewJson.int("code", -1)
            if (code != 0) {
                result.addProperty("success", false)
                result.addProperty("message", viewJson.str("message") ?: "获取视频详情失败 (错误码: $code)")
                return@withContext result
            }

            val data = viewJson.obj("data")
            if (data == null) {
                result.addProperty("success", false)
                result.addProperty("message", "视频详情数据为空")
                return@withContext result
            }

            val title = data.str("title", "未知标题") ?: "未知标题"
            val pic = data.str("pic", "") ?: ""
            val duration = data.long("duration", 0L)
            val owner = data.obj("owner")
            val author = owner.str("name", "B站UP主") ?: "B站UP主"

            // Multi-part (分P) resolution
            val pagesArr = data.arr("pages")
            var activeCid = data.long("cid", 0L)
            var activePartTitle = ""
            var activePage = 1

            if (pagesArr != null && pagesArr.size() > 0) {
                if (targetCid != null && targetCid > 0) {
                    for (p in pagesArr) {
                        val pObj = p.asSafeObject()
                        if (pObj != null && pObj.long("cid", 0L) == targetCid) {
                            activeCid = targetCid
                            activePartTitle = pObj.str("part") ?: ""
                            activePage = pObj.int("page", 1)
                            break
                        }
                    }
                } else if (targetPage > 1 && targetPage <= pagesArr.size()) {
                    val pObj = pagesArr.get(targetPage - 1).asSafeObject()
                    if (pObj != null) {
                        activeCid = pObj.long("cid", activeCid)
                        activePartTitle = pObj.str("part") ?: ""
                        activePage = targetPage
                    }
                } else {
                    val firstObj = pagesArr.get(0).asSafeObject()
                    if (firstObj != null) {
                        activePartTitle = firstObj.str("part") ?: ""
                    }
                }
            }

            var playData: JsonObject? = null

            // 1. Try WBI playurl first (highest quality DASH streams)
            try {
                val (imgKey, subKey) = getWbiKeys(cookies)
                val params = mutableMapOf(
                    "bvid" to bvid,
                    "cid" to activeCid.toString(),
                    "qn" to "127",
                    "fnval" to "4048",
                    "fourk" to "1"
                )
                WbiSigner.encWbi(params, imgKey, subKey)

                val playQuery = params.map { "${it.key}=${it.value}" }.joinToString("&")
                val playUrl = "https://api.bilibili.com/x/player/wbi/playurl?$playQuery"

                val playReq = Request.Builder()
                    .url(playUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .header("Cookie", finalCookie)
                    .build()

                client.newCall(playReq).execute().use { playResp ->
                    val playBody = playResp.body?.string() ?: ""
                    val playJson = playBody.parseAsJsonObject()
                    if (playJson != null && playJson.int("code", -1) == 0) {
                        val pData = playJson.obj("data")
                        if (pData != null && (pData.obj("dash") != null || pData.arr("durl") != null)) {
                            playData = pData
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Fallback to standard web playurl (no WBI required)
            if (playData == null) {
                try {
                    val fallbackPlayUrl = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$activeCid&qn=80&fnval=4048&fourk=1"
                    val playReq = Request.Builder()
                        .url(fallbackPlayUrl)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", REFERER)
                        .header("Cookie", finalCookie)
                        .build()

                    client.newCall(playReq).execute().use { playResp ->
                        val playBody = playResp.body?.string() ?: ""
                        val playJson = playBody.parseAsJsonObject()
                        if (playJson != null && playJson.int("code", -1) == 0) {
                            val pData = playJson.obj("data")
                            if (pData != null && (pData.obj("dash") != null || pData.arr("durl") != null)) {
                                playData = pData
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 3. Fallback to legacy single MP4 durl
            if (playData == null) {
                try {
                    val legacyPlayUrl = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$activeCid&qn=64&fnval=0"
                    val playReq = Request.Builder()
                        .url(legacyPlayUrl)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", REFERER)
                        .header("Cookie", finalCookie)
                        .build()

                    client.newCall(playReq).execute().use { playResp ->
                        val playBody = playResp.body?.string() ?: ""
                        val playJson = playBody.parseAsJsonObject()
                        if (playJson != null && playJson.int("code", -1) == 0) {
                            playData = playJson.obj("data")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (playData == null) {
                result.addProperty("success", false)
                result.addProperty("message", "未能获取到视频播放流地址，可能受B站地区限制或需登录账号")
                return@withContext result
            }

            result.addProperty("success", true)
            result.addProperty("platform", "bilibili")
            result.addProperty("bvid", bvid)
            result.addProperty("cid", activeCid)
            result.addProperty("current_page", activePage)
            result.addProperty("part_title", activePartTitle)
            result.addProperty("title", title)
            result.addProperty("pic", pic)
            result.addProperty("author", author)
            result.addProperty("duration", duration)
            result.add("play_data", playData)
            if (pagesArr != null) {
                result.add("pages", pagesArr)
            }

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
            val buvid = ensureBuvid()
            val req = Request.Builder()
                .url("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Cookie", buvid)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val json = body.parseAsJsonObject()
                return@withContext json ?: JsonObject().apply {
                    addProperty("code", -1)
                    addProperty("message", "生成二维码返回格式错误")
                }
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
            val buvid = ensureBuvid()
            val url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$qrcodeKey"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Cookie", buvid)
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val json = body.parseAsJsonObject() ?: JsonObject().apply {
                    addProperty("code", -1)
                    addProperty("message", "响应格式解析失败")
                }
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
