package com.jarvis.assistant.core.recovery

import android.content.Context

data class InterruptedTask(val id: String, val request: String, val timestamp: Long)
class CrashRecoveryStore(context: Context) {
    private val p=context.getSharedPreferences("jarvis_recovery",Context.MODE_PRIVATE)
    fun markStarted(id:String,request:String){p.edit().putString("id",id).putString("request",request).putLong("time",System.currentTimeMillis()).apply()}
    fun markFinished(){p.edit().clear().apply()}
    fun interrupted(): InterruptedTask? { val id=p.getString("id",null) ?: return null; return InterruptedTask(id,p.getString("request","") ?: "",p.getLong("time",0)) }
}
