package com.jarvis.assistant.core.privacy

enum class RetentionMode { KEEP, AUTO_DELETE, DELETE_NOW }
data class DataRetentionPolicy(val conversations: RetentionMode=RetentionMode.KEEP,val activityLogs: RetentionMode=RetentionMode.KEEP,val memory: RetentionMode=RetentionMode.KEEP,val taskHistory: RetentionMode=RetentionMode.KEEP,val diagnostics: RetentionMode=RetentionMode.KEEP)
