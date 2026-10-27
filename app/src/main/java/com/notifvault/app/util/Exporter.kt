package com.notifvault.app.util

import com.notifvault.app.data.NotificationRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns vault records into files the user can keep elsewhere.
 *
 * Exports are produced as text and handed to Android's document picker, so the
 * user chooses the destination and the app never needs storage permissions.
 */
object Exporter {

    enum class Format(val extension: String, val mimeType: String) {
        CSV("csv", "text/csv"),
        JSON("json", "application/json"),
    }

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileStampFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun fileName(format: Format, prefix: String = "notification-vault"): String =
        "$prefix-${fileStampFormat.format(Date())}.${format.extension}"

    fun render(records: List<NotificationRecord>, format: Format): String = when (format) {
        Format.CSV -> toCsv(records)
        Format.JSON -> toJson(records)
    }

    // -------------------------------------------------------------------- CSV

    private fun toCsv(records: List<NotificationRecord>): String = buildString {
        append(
            "timestamp,app,package,title,sender,conversation,body,actions," +
                "channel,category,is_ongoing,is_read,starred,during_quiet_hours\n",
        )
        records.forEach { r ->
            val cells = listOf(
                iso(r.postedAt),
                r.appLabel,
                r.packageName,
                r.title,
                r.sender,
                r.conversationTitle,
                r.body,
                r.actions,
                r.channelId,
                r.category,
                r.isOngoing.toString(),
                r.isRead.toString(),
                r.starred.toString(),
                r.duringQuietHours.toString(),
            )
            append(cells.joinToString(",") { escapeCsv(it) })
            append('\n')
        }
    }

    /** Quotes a CSV cell, doubling any embedded quotes. */
    private fun escapeCsv(value: String?): String {
        val v = value.orEmpty()
        return "\"" + v.replace("\"", "\"\"") + "\""
    }

    // ------------------------------------------------------------------- JSON

    private fun toJson(records: List<NotificationRecord>): String = buildString {
        append("{\n")
        append("  \"generator\": \"Notification Vault\",\n")
        append("  \"exported_at\": \"${iso(System.currentTimeMillis())}\",\n")
        append("  \"count\": ${records.size},\n")
        append("  \"notifications\": [\n")
        records.forEachIndexed { index, r ->
            append("    {")
            append("\"timestamp\": \"${iso(r.postedAt)}\", ")
            append("\"app\": ${jsonString(r.appLabel)}, ")
            append("\"package\": ${jsonString(r.packageName)}, ")
            append("\"title\": ${jsonString(r.title)}, ")
            append("\"sender\": ${jsonString(r.sender)}, ")
            append("\"conversation\": ${jsonString(r.conversationTitle)}, ")
            append("\"body\": ${jsonString(r.body)}, ")
            append("\"actions\": ${jsonString(r.actions)}, ")
            append("\"channel\": ${jsonString(r.channelId)}, ")
            append("\"category\": ${jsonString(r.category)}, ")
            append("\"is_ongoing\": ${r.isOngoing}, ")
            append("\"is_read\": ${r.isRead}, ")
            append("\"starred\": ${r.starred}, ")
            append("\"during_quiet_hours\": ${r.duringQuietHours}")
            append("}")
            if (index != records.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n")
        append("}\n")
    }

    /** Quotes a JSON string, escaping the characters JSON requires. */
    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        val sb = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    // ------------------------------------------------------------------ dates

    private fun iso(epochMillis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date(epochMillis))
    }

    @Suppress("unused")
    private fun readable(epochMillis: Long): String = timestampFormat.format(Date(epochMillis))
}
