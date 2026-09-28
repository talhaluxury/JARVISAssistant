package com.jarvis.assistant.core

import com.jarvis.assistant.core.cache.SmartCommandCache
import com.jarvis.assistant.core.input.InputNormalizer
import com.jarvis.assistant.core.input.InputLanguage
import com.jarvis.assistant.core.input.LanguageDetector
import com.jarvis.assistant.core.priority.CommandPriority
import com.jarvis.assistant.core.priority.CommandPriorityResolver
import com.jarvis.assistant.core.recovery.RecoveryPolicy
import com.jarvis.assistant.agent.FailureReason
import com.jarvis.assistant.command.JarvisCommand
import org.junit.Assert.*
import org.junit.Test

class OmegaCoreTest {
    @Test fun normalizerCollapsesWhitespace() = assertEquals("open camera", InputNormalizer.normalize("  open   camera "))
    @Test fun detectsUrdu() = assertEquals(InputLanguage.URDU, LanguageDetector.detect("کیمرہ کھولو"))
    @Test fun detectsRomanUrdu() = assertEquals(InputLanguage.ROMAN_URDU, LanguageDetector.detect("wifi band karo"))
    @Test fun emergencyHasHighestPriority() = assertEquals(CommandPriority.EMERGENCY, CommandPriorityResolver.resolve(JarvisCommand.StopAction))
    @Test fun cacheRoundTrip() { val c=SmartCommandCache<String,String>(10_000); c.put("x","y"); assertEquals("y",c.get("x")); c.invalidate("x"); assertNull(c.get("x")) }
    @Test fun recoveryBecomesUserQuestionAfterRetries() = assertEquals(com.jarvis.assistant.core.recovery.RecoveryStrategy.ASK_USER, RecoveryPolicy().strategy(FailureReason.UNKNOWN, 2))
}
