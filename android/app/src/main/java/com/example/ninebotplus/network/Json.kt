package com.example.ninebotplus.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val NinePlusJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    coerceInputValues = true
}

fun parseJsonElement(text: String): JsonElement = NinePlusJson.parseToJsonElement(text)

fun stringBody(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
    for ((key, value) in pairs) put(key, value)
}
