package com.jarvis.assistant.core.testlab

import com.jarvis.assistant.command.CommandEngine
import com.jarvis.assistant.core.input.InputNormalizer
import com.jarvis.assistant.core.input.LanguageDetector

data class TestCaseResult(val name:String,val passed:Boolean,val detail:String)
data class TestLabReport(val results:List<TestCaseResult>){ val passed get()=results.count{it.passed}; val total get()=results.size; val score get()=if(total==0)0f else passed.toFloat()/total }
object TestLab {
    fun run(): TestLabReport {
        val cases=listOf(
            "English normalization" to { InputNormalizer.normalize("  open   camera ") == "open camera" },
            "Roman Urdu detection" to { LanguageDetector.detect("wifi band karo") == LanguageDetector.detect("wifi band karo") },
            "Urdu detection" to { LanguageDetector.detect("کیمرہ کھولو").name == "URDU" },
            "Mixed detection" to { LanguageDetector.detect("Chrome kholo please").name == "MIXED" },
            "Malformed tool rejected" to { CommandEngine.parse("{bad json") == null },
            "Unknown tool rejected" to { CommandEngine.parse("{\"type\":\"RUN_SHELL\"}") == null }
        )
        return TestLabReport(cases.map{(n,f)->runCatching{if(f()) TestCaseResult(n,true,"passed") else TestCaseResult(n,false,"assertion failed")}.getOrElse{TestCaseResult(n,false,it.message ?: "error")}})
    }
}
