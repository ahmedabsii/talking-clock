package com.absi.talkingclock.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException

enum class EventKind { SPOKEN, SKIPPED, PROBLEM, INFO }

data class Event(
    val at: Long,
    val kind: EventKind,
    val message: String,
    /** How long after its scheduled minute an announcement started playing. */
    val delayMs: Long? = null,
) {
    internal fun encode(): String =
        listOf(at.toString(), kind.name, delayMs?.toString().orEmpty(), message.replace(CONTROL_CHARS, " "))
            .joinToString("\t")

    internal companion object {
        private val CONTROL_CHARS = Regex("[\t\r\n]")

        fun decode(line: String): Event? {
            val parts = line.split('\t', limit = 4)
            if (parts.size != 4) return null
            val at = parts[0].toLongOrNull() ?: return null
            val kind = EventKind.entries.firstOrNull { it.name == parts[1] } ?: return null
            return Event(at, kind, parts[3], parts[2].toLongOrNull())
        }
    }
}

/**
 * What the History screen shows: a small tab-separated file, newest entry last, capped at
 * [MAX_EVENTS]. It lives in device-protected storage, so announcements made before the first
 * unlock after a reboot are recorded too.
 */
class EventLog(private val file: File) {
    private val lock = Any()
    private val _version = MutableStateFlow(0)

    /** Changes whenever an event is added; collect it to know when to re-read [recent]. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun add(kind: EventKind, message: String, delayMs: Long? = null, at: Long = System.currentTimeMillis()) {
        val event = Event(at, kind, message, delayMs)
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                file.appendText(event.encode() + "\n")
                trimIfNeeded()
            } catch (e: IOException) {
                Log.w(TAG, "Could not write history", e)
            }
        }
        Log.i(TAG, "${kind.name}: $message" + (delayMs?.let { " (+$it ms)" } ?: ""))
        _version.update { it + 1 }
    }

    /** Newest first. */
    fun recent(limit: Int = MAX_EVENTS): List<Event> {
        val lines = synchronized(lock) {
            try {
                if (file.exists()) file.readLines() else emptyList()
            } catch (e: IOException) {
                emptyList()
            }
        }
        return lines.mapNotNull { Event.decode(it) }.takeLast(limit).asReversed()
    }

    // Trims in batches rather than on every write, so a write is normally just an append.
    private fun trimIfNeeded() {
        val lines = file.readLines()
        if (lines.size <= MAX_EVENTS + TRIM_SLACK) return
        val tmp = File(file.path + ".tmp")
        tmp.writeText(lines.takeLast(MAX_EVENTS).joinToString(separator = "\n", postfix = "\n"))
        if (!tmp.renameTo(file)) tmp.delete()
    }

    companion object {
        const val MAX_EVENTS = 200
        private const val TRIM_SLACK = 50
        private const val TAG = "TalkingClock"
    }
}
