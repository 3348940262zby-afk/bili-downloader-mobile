package com.bilidownloader.mobile.engine

import com.google.gson.JsonObject

object MediaParserDispatcher {

    fun detectPlatform(input: String): String {
        val s = input.lowercase()
        if (s.contains("douyin.com") || s.contains("iesdouyin.com")) {
            return "douyin"
        }
        if (s.contains("kuaishou.com") || s.contains("gifshow.com")) {
            return "kuaishou"
        }
        if (s.contains("bilibili.com") || s.contains("b23.tv") || s.contains("bv1") || Regex("(?i)BV[a-zA-Z0-9]{10}").containsMatchIn(input)) {
            return "bilibili"
        }
        return "unknown"
    }

    suspend fun parse(input: String, cookies: String = ""): JsonObject {
        val platform = detectPlatform(input)
        return when (platform) {
            "douyin" -> DouyinParser.parse(input)
            "kuaishou" -> KuaishouParser.parse(input)
            "bilibili" -> {
                val bvid = BiliParser.resolveBvid(input)
                if (bvid != null) {
                    BiliParser.parseVideo(bvid, cookies)
                } else {
                    val res = JsonObject()
                    res.addProperty("success", false)
                    res.addProperty("message", "未能从输入中提取到有效的 B站 BV号或短链接")
                    res
                }
            }
            else -> {
                // If unknown, try Bilibili resolver or fallback
                val bvid = BiliParser.resolveBvid(input)
                if (bvid != null) {
                    BiliParser.parseVideo(bvid, cookies)
                } else {
                    val res = JsonObject()
                    res.addProperty("success", false)
                    res.addProperty("message", "未能自动识别平台，请输入包含 B站/抖音/快手 的有效链接")
                    res
                }
            }
        }
    }
}
