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
        if (s.contains("bilibili.com") || s.contains("b23.tv") || s.contains("bili2233.cn") || 
            Regex("(?i)BV[a-zA-Z0-9]{10}").containsMatchIn(input) || 
            Regex("(?i)\\bav\\d+\\b").containsMatchIn(input) ||
            Regex("(?i)\\bep\\d+\\b").containsMatchIn(input) ||
            Regex("(?i)\\bss\\d+\\b").containsMatchIn(input)) {
            return "bilibili"
        }
        return "unknown"
    }

    suspend fun parse(input: String, cookies: String = ""): JsonObject {
        val platform = detectPlatform(input)
        when (platform) {
            "douyin" -> return DouyinParser.parse(input)
            "kuaishou" -> return KuaishouParser.parse(input)
            "bilibili" -> {
                val target = BiliParser.resolveTarget(input)
                if (target != null) {
                    val pageParam = Regex("(?i)[?&]p=(\\d+)").find(input)?.groupValues?.get(1)?.toIntOrNull() ?: target.page
                    val cidParam = Regex("(?i)[?&]cid=(\\d+)").find(input)?.groupValues?.get(1)?.toLongOrNull() ?: target.cid
                    return BiliParser.parseVideo(target.bvid, cookies, targetPage = pageParam, targetCid = cidParam)
                } else {
                    val res = JsonObject()
                    res.addProperty("success", false)
                    res.addProperty("message", "未能从输入中提取到有效的 B站 BV号或短链接")
                    return res
                }
            }
        }

        // Fallback checks for unknown platform
        val fallbackTarget = BiliParser.resolveTarget(input)
        if (fallbackTarget != null) {
            val pageParam = Regex("(?i)[?&]p=(\\d+)").find(input)?.groupValues?.get(1)?.toIntOrNull() ?: fallbackTarget.page
            val cidParam = Regex("(?i)[?&]cid=(\\d+)").find(input)?.groupValues?.get(1)?.toLongOrNull() ?: fallbackTarget.cid
            return BiliParser.parseVideo(fallbackTarget.bvid, cookies, targetPage = pageParam, targetCid = cidParam)
        }
        if (DouyinParser.extractUrl(input) != null) {
            return DouyinParser.parse(input)
        }
        if (KuaishouParser.extractUrl(input) != null) {
            return KuaishouParser.parse(input)
        }

        val res = JsonObject()
        res.addProperty("success", false)
        res.addProperty("message", "未能自动识别平台，请输入包含 B站 / 抖音 / 快手 的有效链接或分享文本")
        return res
    }
}
