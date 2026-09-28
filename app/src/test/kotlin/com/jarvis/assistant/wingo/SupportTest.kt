package com.jarvis.assistant.wingo

import com.jarvis.assistant.wingo.analysis.EdgeAssessment
import com.jarvis.assistant.wingo.analysis.ModelOutput
import com.jarvis.assistant.wingo.analysis.WinGoBacktestEngine
import com.jarvis.assistant.wingo.analysis.WinGoPrediction
import com.jarvis.assistant.wingo.data.NONE
import com.jarvis.assistant.wingo.data.toOutcome
import com.jarvis.assistant.wingo.data.toRecord
import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.ConfidenceLevel
import com.jarvis.assistant.wingo.domain.RoundResult
import com.jarvis.assistant.wingo.domain.Signal
import com.jarvis.assistant.wingo.ocr.CsvImporter
import com.jarvis.assistant.wingo.ocr.FrameDiff
import com.jarvis.assistant.wingo.ocr.GameRegionDetector
import com.jarvis.assistant.wingo.ocr.OcrLine
import com.jarvis.assistant.wingo.voice.WinGoNarrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SupportTest {

    private fun period(seq: Int) = "2026092010005" + seq.toString().padStart(4, '0')

    // ---- CSV import --------------------------------------------------------------------------------------

    @Test
    fun csvImportValidatesDeduplicatesAndSorts() {
        val csv = """
            Period,Number,BigSmall,Color
            ${period(1299)},7,Big,Green
            ${period(1298)},2,Small,Red
            ${period(1298)},2,Small,Red
            ${period(1297)},7,Small,Green
            2026092010005129,3,Big
            ${period(1296)},0
        """.trimIndent()
        val r = CsvImporter.parse(csv)
        assertEquals(listOf(period(1296), period(1298), period(1299)), r.results.map { it.period })
        assertEquals(1, r.duplicates)
        assertEquals(2, r.rejectedLines.size) // label disagrees with number; malformed period
    }

    // ---- prediction storage ----------------------------------------------------------------------------------

    private fun prediction(side: BigSmall?, signal: Signal) = WinGoPrediction(
        forPeriod = period(1300), side = side, probBig = 0.62, confidence = 0.62,
        level = ConfidenceLevel.MEDIUM, signal = signal, agree = 4, voting = 7, totalModels = 7,
        isCandidate = true, waitReason = null, outputs = emptyList<ModelOutput>(), weights = emptyMap(),
        historySize = 500, edge = EdgeAssessment(0, 0, null, null, false, "")
    )

    @Test
    fun aPredictionIsStoredBeforeItsResultIsKnown() {
        val record = prediction(BigSmall.BIG, Signal.MEDIUM).toRecord(period(1300), 123L)
        assertEquals("BIG", record.prediction)
        assertEquals("MEDIUM", record.signal)
        assertNull(record.actualResult)
        assertNull(record.correct)
        assertNull("an unresolved prediction must not count towards accuracy", record.toOutcome())
    }

    @Test
    fun resolvedRecordsBecomeOutcomesAndNoLeanRecordsDoNot() {
        val resolved = prediction(BigSmall.BIG, Signal.MEDIUM).toRecord(period(1300), 1L)
            .copy(actualResult = "SMALL", correct = false)
        val outcome = resolved.toOutcome()
        assertNotNull(outcome)
        assertFalse(outcome!!.correct)
        assertTrue(outcome.wasSignal)

        val none = prediction(null, Signal.WAIT).toRecord(period(1300), 1L).copy(actualResult = "BIG", correct = false)
        assertEquals(NONE, none.prediction)
        assertNull(none.toOutcome())
    }

    // ---- narrator wording ---------------------------------------------------------------------------------------------

    private val banned = listOf("guaranteed", "sure win", "100% accurate", "risk-free", "cannot lose", "fixed match")

    @Test
    fun wordingNeverPromisesAnOutcome() {
        val signalState = WinGoUiState(prediction = prediction(BigSmall.BIG, Signal.MEDIUM), predictionPeriod = period(1300))
        val waitState = WinGoUiState(
            prediction = prediction(BigSmall.BIG, Signal.WAIT).copy(waitReason = "WAIT — insufficient signal."),
            predictionPeriod = period(1300)
        )
        val texts = listOf(
            WinGoNarrator.signalText(signalState), WinGoNarrator.spokenSignal(signalState), WinGoNarrator.why(signalState),
            WinGoNarrator.signalText(waitState), WinGoNarrator.spokenSignal(waitState), WinGoNarrator.why(waitState),
            WinGoNarrator.accuracy(signalState, null), WinGoNarrator.HELP
        )
        for (t in texts) for (word in banned) assertFalse("'$word' found in: $t", t.lowercase().contains(word))
    }

    @Test
    fun signalTextShowsConfidenceStrengthAndAgreement() {
        val text = WinGoNarrator.signalText(WinGoUiState(prediction = prediction(BigSmall.BIG, Signal.MEDIUM)))
        assertTrue(text.contains("BIG"))
        assertTrue(text.contains("62%"))
        assertTrue(text.contains("MEDIUM"))
        assertTrue(text.contains("4/7"))
    }

    @Test
    fun waitIsReportedAsWaitWithItsReason() {
        val waitState = WinGoUiState(prediction = prediction(BigSmall.BIG, Signal.WAIT).copy(waitReason = "WAIT — insufficient signal."))
        assertTrue(WinGoNarrator.signalText(waitState).startsWith("WAIT"))
        assertEquals("Insufficient historical data.", WinGoNarrator.signalText(WinGoUiState(message = "Insufficient historical data.")))
    }

    // ---- change detection and region detection ------------------------------------------------------------------------

    @Test
    fun frameDiffOnlyTriggersOnRealChange() {
        val a = IntArray(100) { 50 }
        assertFalse(FrameDiff.differs(a, IntArray(100) { 51 }, 3.0))
        assertTrue(FrameDiff.differs(a, IntArray(100) { 90 }, 3.0))
        assertTrue(FrameDiff.differs(null, a, 3.0))
        assertTrue(FrameDiff.differs(IntArray(10), a, 3.0)) // size mismatch
    }

    private fun word(text: String, left: Int, top: Int) = OcrLine(text, left, top, left + text.length * 12, top + 30, 0.95f)

    private fun tableLines(rows: Int): List<OcrLine> {
        val lines = ArrayList<OcrLine>()
        for (r in 0 until rows) {
            val top = 1400 + r * 70
            val n = (r * 3) % 10
            lines.add(word(period(1299 - r), 20, top))
            lines.add(word(n.toString(), 500, top - 4))
            lines.add(word(if (n >= 5) "Big" else "Small", 640, top))
        }
        return lines
    }

    @Test
    fun regionDetectorRequiresSeveralRealRowsBeforeClaimingTheGameIsVisible() {
        val detector = GameRegionDetector()
        val notVisible = detector.detect(tableLines(2), 1080, 2400)
        assertFalse(notVisible.visible)

        val visible = detector.detect(tableLines(5), 1080, 2400)
        assertTrue(visible.visible)
        val region = visible.historyRegion!!
        assertTrue(region.top < 1400f / 2400f)
        assertTrue(region.bottom > (1400f + 4 * 70f) / 2400f)
    }

    // ---- the analyzer's own claims ----------------------------------------------------------------------------------

    @Test
    fun backtestReportTextContainsTheRequiredSections() {
        val results = List(300) { RoundResult(period(it + 1), (it * 7) % 10, 0L) }
        val text = WinGoBacktestEngine().run(results).toText()
        for (needle in listOf("Predictions:", "Correct:", "Wrong:", "Accuracy:", "Skipped", "Avg Confidence:", "Verdict:")) {
            assertTrue("missing '$needle'", text.contains(needle))
        }
    }

    // ---- no automation: the module must never touch the game ------------------------------------------------------------

    @Test
    fun wingoAndQuotexModulesContainNoAutomationOrInputInjection() {
        val forbidden = listOf(
            "dispatchGesture", "GestureDescription", "performAction", "performGlobalAction", "AccessibilityService",
            "JarvisAccessibilityService", "AndroidActionExecutor", "Runtime.getRuntime", "ProcessBuilder",
            "injectInputEvent", "MotionEvent.obtain", "Instrumentation", "sendKeyEvent"
        )
        val offenders = ArrayList<String>()
        for (module in listOf("wingo", "quotex")) {
            val candidates = listOf(
                File("src/main/kotlin/com/jarvis/assistant/$module"),
                File("app/src/main/kotlin/com/jarvis/assistant/$module")
            )
            val dir = candidates.firstOrNull { it.isDirectory }
            assertNotNull("$module source folder not found from ${File(".").absolutePath}", dir)
            dir!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                for (token in forbidden) if (text.contains(token)) offenders.add("${file.name}: $token")
            }
        }
        assertTrue("forbidden automation APIs referenced: $offenders", offenders.isEmpty())
    }
}
