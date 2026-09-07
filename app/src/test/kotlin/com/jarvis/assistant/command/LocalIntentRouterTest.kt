package com.jarvis.assistant.command

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalIntentRouterTest {
    @Test fun recognizesRomanUrduScroll() {
        assertEquals(JarvisCommand.ScrollDown, LocalIntentRouter.match("neeche scroll karo"))
    }

    @Test fun recognizesOpenApp() {
        assertEquals(JarvisCommand.OpenApp("YouTube"), LocalIntentRouter.match("YouTube kholo"))
    }
}
