package com.jarvis.assistant.core.time

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class TimeAwareness(private val zone: ZoneId = ZoneId.systemDefault()) {
    fun now(): ZonedDateTime = ZonedDateTime.now(zone)
    fun timeText(): String = now().format(DateTimeFormatter.ofPattern("h:mm a"))
    fun dateText(): String = now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"))
    fun durationMinutes(minutes: Long): ZonedDateTime = now().plusMinutes(minutes.coerceAtLeast(0))
    fun durationSeconds(seconds: Long): ZonedDateTime = now().plusSeconds(seconds.coerceAtLeast(0))
    fun nextOccurrence(hour: Int, minute: Int): ZonedDateTime {
        val candidate = LocalDateTime.of(now().toLocalDate(), LocalTime.of(hour.coerceIn(0,23), minute.coerceIn(0,59))).atZone(zone)
        return if (candidate.isAfter(now())) candidate else candidate.plusDays(1)
    }
}
