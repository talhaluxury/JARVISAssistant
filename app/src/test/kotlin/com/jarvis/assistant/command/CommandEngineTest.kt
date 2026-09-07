package com.jarvis.assistant.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandEngineTest {
    @Test fun parsesOpenApp() {
        assertEquals(JarvisCommand.OpenApp("YouTube"), CommandEngine.parse("{\"type\":\"OPEN_APP\",\"target\":\"YouTube\"}"))
    }

    @Test fun rejectsInvalidAlarm() {
        assertNull(CommandEngine.parse("{\"type\":\"SET_ALARM\",\"hour\":25,\"minute\":0}"))
    }

    @Test fun parsesBoundedPlan() {
        val plan = com.jarvis.assistant.agent.AgentPlanner().parsePlan(
            "{\"type\":\"AGENT_PLAN\",\"actions\":[{\"type\":\"OPEN_APP\",\"target\":\"YouTube\"},{\"type\":\"OPEN_SETTINGS\",\"target\":\"wifi\"}]}"
        )
        assertEquals(2, plan?.actions?.size)
    }
}
