package com.jarvis.assistant.accessibility

/**
 * The closed set of primitive on-screen actions JARVIS can perform through the
 * Accessibility Service. A multi-app "do this in that app" request from the AI is
 * broken down into a small ordered list of these steps (see JarvisCommand.Automate),
 * which JarvisAccessibilityService then attempts to carry out one at a time as the
 * screen updates. Nothing here bypasses Android's permission model — every action is
 * exactly what a sighted user could do by tapping/typing on screen themselves, and the
 * user must have explicitly turned on the Accessibility Service for JARVIS beforehand.
 */
sealed class AutomationStep {
    /** Find a node whose visible text matches (contains, case-insensitive) and tap it. */
    data class TapText(val text: String) : AutomationStep()

    /** Find a node whose content description matches (contains, case-insensitive) and tap it. */
    data class TapDescription(val description: String) : AutomationStep()

    /** Type into the currently focused editable field (or the first editable field found). */
    data class TypeText(val text: String) : AutomationStep()

    /** Pause before the next step, to give a screen time to load. */
    data class Wait(val milliseconds: Long) : AutomationStep()

    object PressBack : AutomationStep()
    object PressHome : AutomationStep()
    object ScrollForward : AutomationStep()
    object ScrollBackward : AutomationStep()
}
