package com.jarvis.assistant.core.observability

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

data class PerformanceSnapshot(val commands: Long, val failures: Long, val totalLatencyMs: Long, val averageLatencyMs: Long, val automationSuccess: Long, val automationFailures: Long)
class PerformanceTelemetry {
    private val commands=AtomicLong(); private val failures=AtomicLong(); private val latency=LongAdder(); private val autoOk=AtomicLong(); private val autoFail=AtomicLong()
    fun commandFinished(durationMs: Long, success: Boolean) { commands.incrementAndGet(); latency.add(durationMs.coerceAtLeast(0)); if(!success) failures.incrementAndGet() }
    fun automationFinished(success: Boolean) { if(success) autoOk.incrementAndGet() else autoFail.incrementAndGet() }
    fun snapshot(): PerformanceSnapshot { val c=commands.get(); return PerformanceSnapshot(c,failures.get(),latency.sum(),if(c==0L)0 else latency.sum()/c,autoOk.get(),autoFail.get()) }
}
