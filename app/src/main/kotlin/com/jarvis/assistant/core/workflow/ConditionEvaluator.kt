package com.jarvis.assistant.core.workflow

import com.jarvis.assistant.agent.PhoneContext

/** Deterministic, auditable conditions. Deliberately small grammar; unknown expressions never pass. */
class ConditionEvaluator {
    fun evaluate(expression: String, context: PhoneContext): Boolean = when {
        expression.equals("network_available", true) -> context.networkConnected
        expression.equals("wifi_on", true) -> context.wifiEnabled
        expression.equals("bluetooth_on", true) -> context.bluetoothEnabled
        expression.startsWith("battery < ") -> context.batteryPercent < expression.substringAfter("battery < ").trim().toIntOrNull().let { it ?: Int.MIN_VALUE }
        expression.startsWith("battery <= ") -> context.batteryPercent <= expression.substringAfter("battery <= ").trim().toIntOrNull().let { it ?: Int.MIN_VALUE }
        expression.startsWith("battery > ") -> context.batteryPercent > expression.substringAfter("battery > ").trim().toIntOrNull().let { it ?: Int.MAX_VALUE }
        expression.startsWith("app = ", true) -> context.packageName.equals(expression.substringAfter('=').trim(), true)
        else -> false
    }
}
