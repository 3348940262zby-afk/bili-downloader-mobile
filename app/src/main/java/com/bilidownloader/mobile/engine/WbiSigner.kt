package com.bilidownloader.mobile.engine

import java.net.URLEncoder
import java.security.MessageDigest

object WbiSigner {
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52
    )

    fun getMixinKey(orig: String): String {
        val sb = StringBuilder()
        for (idx in MIXIN_KEY_ENC_TAB) {
            if (idx < orig.length) {
                sb.append(orig[idx])
            }
        }
        return if (sb.length > 32) sb.substring(0, 32) else sb.toString()
    }

    fun encWbi(params: MutableMap<String, String>, imgKey: String, subKey: String): Map<String, String> {
        val mixinKey = getMixinKey(imgKey + subKey)
        val currTime = (System.currentTimeMillis() / 1000).toString()
        params["wts"] = currTime

        // Sort keys alphabetically
        val sortedKeys = params.keys.sorted()
        val queryParts = mutableListOf<String>()

        for (k in sortedKeys) {
            val v = params[k] ?: ""
            // Filter out forbidden characters: ! ' ( ) *
            val filteredVal = v.replace(Regex("[!'()*]"), "")
            val encodedVal = URLEncoder.encode(filteredVal, "UTF-8")
            val encodedKey = URLEncoder.encode(k, "UTF-8")
            queryParts.add("$encodedKey=$encodedVal")
        }

        val queryString = queryParts.joinToString("&")
        val stringToHash = queryString + mixinKey
        val wbiSign = md5(stringToHash)

        params["w_rid"] = wbiSign
        return params
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
