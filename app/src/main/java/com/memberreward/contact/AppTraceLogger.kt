package com.memberreward.contact

import android.util.Log
import java.util.UUID

object AppTraceLogger {
    private const val TAG = "ContactTrace"

    fun newTraceId(prefix: String = "trace"): String {
        val shortId = UUID.randomUUID().toString().substring(0, 8)
        return "$prefix-${System.currentTimeMillis()}-$shortId"
    }

    fun d(component: String, traceId: String, event: String, vararg fields: Pair<String, Any?>) {
        Log.d(TAG, buildMessage(component, traceId, event, *fields))
    }

    fun i(component: String, traceId: String, event: String, vararg fields: Pair<String, Any?>) {
        Log.i(TAG, buildMessage(component, traceId, event, *fields))
    }

    fun w(component: String, traceId: String, event: String, vararg fields: Pair<String, Any?>) {
        Log.w(TAG, buildMessage(component, traceId, event, *fields))
    }

    fun e(
        component: String,
        traceId: String,
        event: String,
        error: Throwable? = null,
        vararg fields: Pair<String, Any?>
    ) {
        Log.e(TAG, buildMessage(component, traceId, event, *fields), error)
    }

    private fun buildMessage(
        component: String,
        traceId: String,
        event: String,
        vararg fields: Pair<String, Any?>
    ): String {
        val suffix = fields
            .mapNotNull { (key, value) ->
                val normalized = normalizeValue(value)
                if (normalized.isEmpty()) null else "$key=$normalized"
            }
            .joinToString(" ")

        return buildString {
            append("[trace=")
            append(traceId)
            append("] [")
            append(component)
            append("] event=")
            append(event)
            if (suffix.isNotEmpty()) {
                append(' ')
                append(suffix)
            }
        }
    }

    private fun normalizeValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> value.replace(Regex("\\s+"), " ").trim()
            else -> value.toString().replace(Regex("\\s+"), " ").trim()
        }
    }
}
