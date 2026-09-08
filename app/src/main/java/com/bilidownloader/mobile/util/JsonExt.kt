package com.bilidownloader.mobile.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

fun String?.parseAsJsonObject(): JsonObject? {
    if (this.isNullOrBlank()) return null
    return try {
        val elem = JsonParser.parseString(this)
        if (elem != null && elem.isJsonObject) elem.asJsonObject else null
    } catch (_: Exception) {
        null
    }
}

fun JsonElement?.asSafeObject(): JsonObject? = if (this != null && this.isJsonObject) this.asJsonObject else null
fun JsonElement?.asSafeArray(): JsonArray? = if (this != null && this.isJsonArray) this.asJsonArray else null

fun JsonObject?.obj(key: String): JsonObject? = this?.get(key)?.asSafeObject()
fun JsonObject?.arr(key: String): JsonArray? = this?.get(key)?.asSafeArray()

fun JsonObject?.str(key: String, default: String? = null): String? {
    val elem = this?.get(key)
    if (elem != null && !elem.isJsonNull && elem.isJsonPrimitive) {
        return elem.asString
    }
    return default
}

fun JsonObject?.int(key: String, default: Int = 0): Int {
    val elem = this?.get(key)
    if (elem != null && !elem.isJsonNull && elem.isJsonPrimitive) {
        return runCatching { elem.asInt }.getOrDefault(default)
    }
    return default
}

fun JsonObject?.long(key: String, default: Long = 0L): Long {
    val elem = this?.get(key)
    if (elem != null && !elem.isJsonNull && elem.isJsonPrimitive) {
        return runCatching { elem.asLong }.getOrDefault(default)
    }
    return default
}
