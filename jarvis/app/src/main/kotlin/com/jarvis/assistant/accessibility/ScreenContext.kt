package com.jarvis.assistant.accessibility

data class ScreenElement(
    val text: String,
    val description: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val role: String = "unknown",
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

data class ScreenContext(
    val packageName: String?,
    val elements: List<ScreenElement>
) {
    fun toPromptString(): String = buildString {
        append("CURRENT APP: ${packageName ?: "unknown"}\nVISIBLE ELEMENTS:\n")
        elements.mapNotNull { el ->
            val label = el.text.ifBlank { el.description }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val state = buildString {
                if (el.clickable) append(" clickable")
                if (el.editable) append(" editable")
                if (el.scrollable) append(" scrollable")
                if (el.selected) append(" selected")
                if (!el.enabled) append(" disabled")
            }
            "- $label [${el.role}$state] bounds=${el.left},${el.top},${el.right},${el.bottom}"
        }.distinct().take(40).forEach { appendLine(it) }
    }
}
