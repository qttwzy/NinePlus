package com.example.ninebotplus.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Flexible JSON value mirroring iOS `JSONValue`.
 * NinePlus Platform historically returns mixed snake_case / camelCase and nested
 * envelopes; parsing stays tolerant at this layer so UI/domain never see dirty JSON.
 */
@Serializable
sealed class JsonValue {
    @Serializable
    data class Obj(val value: Map<String, JsonValue>) : JsonValue()

    @Serializable
    data class Arr(val value: List<JsonValue>) : JsonValue()

    @Serializable
    data class Str(val value: String) : JsonValue()

    @Serializable
    data class Num(val value: Double) : JsonValue()

    @Serializable
    data class Bool(val value: Boolean) : JsonValue()

    @Serializable
    data object Null : JsonValue()

    val objectValue: Map<String, JsonValue>?
        get() = (this as? Obj)?.value

    val arrayValue: List<JsonValue>?
        get() = (this as? Arr)?.value

    val stringValue: String?
        get() = when (this) {
            is Str -> value
            is Num -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            is Bool -> value.toString()
            else -> null
        }

    val doubleValue: Double?
        get() = when (this) {
            is Num -> value
            is Str -> value.trim().toDoubleOrNull()
            is Bool -> if (value) 1.0 else 0.0
            else -> null
        }

    val intValue: Int?
        get() = doubleValue?.toInt()

    val boolValue: Boolean?
        get() = when (this) {
            is Bool -> value
            is Num -> value != 0.0
            is Str -> when (value.trim().lowercase()) {
                "1", "true", "yes", "on" -> true
                "0", "false", "no", "off" -> false
                else -> null
            }
            else -> null
        }

    operator fun get(key: String): JsonValue? = objectValue?.get(key)

    val displayText: String
        get() = when (this) {
            is Obj -> if (value.isEmpty()) "{}" else value.entries.sortedBy { it.key }
                .joinToString(", ") { "${it.key}: ${it.value.displayText}" }
            is Arr -> if (value.isEmpty()) "[]" else value.joinToString(", ") { it.displayText }
            is Str -> value
            is Num -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            is Bool -> value.toString()
            Null -> "null"
        }

    companion object {
        fun from(element: JsonElement): JsonValue = when (element) {
            is JsonNull -> Null
            is JsonPrimitive -> when {
                element.isString -> Str(element.content)
                element.booleanOrNull != null -> Bool(element.booleanOrNull!!)
                element.doubleOrNull != null -> Num(element.doubleOrNull!!)
                else -> Str(element.contentOrNull.orEmpty())
            }
            is JsonArray -> Arr(element.map { from(it) })
            is JsonObject -> Obj(element.mapValues { from(it.value) })
            else -> Null
        }

        fun toElement(value: JsonValue): JsonElement = when (value) {
            is Obj -> buildJsonObject {
                value.value.forEach { (k, v) -> put(k, toElement(v)) }
            }
            is Arr -> JsonArray(value.value.map { toElement(it) })
            is Str -> JsonPrimitive(value.value)
            is Num -> JsonPrimitive(value.value)
            is Bool -> JsonPrimitive(value.value)
            Null -> JsonNull
        }

        fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue = Obj(pairs.toMap())

        fun str(value: String): JsonValue = Str(value)

        fun num(value: Double): JsonValue = Num(value)

        fun num(value: Int): JsonValue = Num(value.toDouble())
    }
}

/** Shared lookup helpers used by tolerant parsers. */
object JsonLookup {
    fun firstInt(keys: List<String>, source: Map<String, JsonValue>): Int? {
        for (key in keys) source[key]?.intValue?.let { return it }
        return null
    }

    fun firstInt(keys: List<String>, objects: List<Map<String, JsonValue>>): Int? {
        for (obj in objects) firstInt(keys, obj)?.let { return it }
        return null
    }

    fun firstDouble(keys: List<String>, source: Map<String, JsonValue>): Double? {
        for (key in keys) source[key]?.doubleValue?.let { return it }
        return null
    }

    fun firstDouble(keys: List<String>, objects: List<Map<String, JsonValue>>): Double? {
        for (obj in objects) firstDouble(keys, obj)?.let { return it }
        return null
    }

    fun firstString(keys: List<String>, source: Map<String, JsonValue>): String? {
        for (key in keys) {
            val text = source[key]?.stringValue?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    fun firstString(keys: List<String>, objects: List<Map<String, JsonValue>>): String? {
        for (obj in objects) firstString(keys, obj)?.let { return it }
        return null
    }

    fun firstObject(keys: List<String>, source: Map<String, JsonValue>): Map<String, JsonValue>? {
        for (key in keys) source[key]?.objectValue?.let { return it }
        return null
    }

    fun firstArrayObject(keys: List<String>, source: Map<String, JsonValue>): Map<String, JsonValue>? {
        for (key in keys) {
            val array = source[key]?.arrayValue ?: continue
            for (item in array) {
                item.objectValue?.let { return it }
            }
        }
        return null
    }

    fun payloadObject(root: Map<String, JsonValue>, preferredKeys: List<String>): Map<String, JsonValue> {
        var current = root
        repeat(2) {
            val nested = firstObject(preferredKeys, current)
            if (nested.isNullOrEmpty()) return current
            current = nested
        }
        return current
    }

    fun firstBoolLike(keys: List<String>, source: Map<String, JsonValue>, trueValue: Int): Boolean? {
        for (key in keys) {
            val value = source[key] ?: continue
            val intValue = value.intValue
            if (intValue != null) return intValue == trueValue
            value.boolValue?.let { return it }
        }
        return null
    }

    fun firstBoolLike(keys: List<String>, objects: List<Map<String, JsonValue>>, trueValue: Int): Boolean? {
        for (obj in objects) firstBoolLike(keys, obj, trueValue)?.let { return it }
        return null
    }
}
