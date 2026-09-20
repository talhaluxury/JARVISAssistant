package com.jarvis.assistant.wingo.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A trusted (validated + confirmed) round. The unique period index prevents duplicates. */
@Entity(tableName = "game_results", indices = [Index(value = ["period"], unique = true)])
data class GameResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val period: String,
    val number: Int,
    val bigSmall: String,
    val color: String,
    val timestamp: Long,
    val sourceConfidence: Float
)

/**
 * Every prediction is stored BEFORE its result exists (actualResult/correct are null until the round
 * finishes). [prediction] is the raw ensemble lean ("BIG"/"SMALL"); [signal] says whether it was
 * actually surfaced (WAIT = not surfaced).
 */
@Entity(tableName = "prediction_records", indices = [Index(value = ["period"], unique = true)])
data class PredictionRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val period: String,
    val prediction: String,
    val confidence: Double,
    val signal: String,
    val level: String,
    val modelAgreement: Int,
    val modelCount: Int,
    val candidate: Boolean,
    val actualResult: String? = null,
    val correct: Boolean? = null,
    val timestamp: Long
)
