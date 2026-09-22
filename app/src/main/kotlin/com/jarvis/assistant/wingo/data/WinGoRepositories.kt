package com.jarvis.assistant.wingo.data

import com.jarvis.assistant.wingo.analysis.CallOutcome
import com.jarvis.assistant.wingo.analysis.WinGoPrediction
import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.ConfidenceLevel
import com.jarvis.assistant.wingo.domain.RoundResult
import com.jarvis.assistant.wingo.domain.Signal

fun RoundResult.toEntity(): GameResultEntity =
    GameResultEntity(period = period, number = number, bigSmall = bigSmall.name, color = color, timestamp = timestamp, sourceConfidence = sourceConfidence)

fun GameResultEntity.toDomain(): RoundResult = RoundResult(period, number, timestamp, sourceConfidence)

/** Builds the record that is stored while the round is still running (result unknown). */
fun WinGoPrediction.toRecord(period: String, timestamp: Long): PredictionRecordEntity =
    PredictionRecordEntity(
        period = period,
        prediction = side?.name ?: NONE,
        confidence = confidence,
        signal = signal.name,
        level = level.name,
        modelAgreement = agree,
        modelCount = totalModels,
        candidate = isCandidate,
        timestamp = timestamp,
        probability = probBig,
        historySize = historySize,
        patternUsed = pattern?.context,
        patternSampleSize = pattern?.occurrences ?: 0,
        modelOutputsJson = ModelOutputsCodec.encode(outputs)
    )

/** A resolved record becomes a [CallOutcome]; records with no lean (NONE) or no result yet are skipped. */
fun PredictionRecordEntity.toOutcome(): CallOutcome? {
    val side = BigSmall.parse(prediction) ?: return null
    val actual = BigSmall.parse(actualResult) ?: return null
    return CallOutcome(
        period = period, side = side, confidence = confidence,
        level = runCatching { ConfidenceLevel.valueOf(level) }.getOrDefault(ConfidenceLevel.VERY_LOW),
        signal = runCatching { Signal.valueOf(signal) }.getOrDefault(Signal.WAIT),
        agree = modelAgreement, totalModels = modelCount, actual = actual,
        patternSamples = patternSampleSize, patternLength = patternUsed?.length ?: 0,
        patternKind = if (patternUsed != null) "EXACT" else null
    )
}

const val NONE = "NONE"

class GameHistoryRepository(private val dao: GameResultDao) {
    /** True when the round was new; false when the period already existed (duplicate ignored). */
    suspend fun insertIfNew(result: RoundResult): Boolean = dao.insert(result.toEntity()) != -1L

    /** The most recent [limit] rounds, oldest first. */
    suspend fun latestAscending(limit: Int): List<RoundResult> = dao.latest(limit).map { it.toDomain() }.reversed()

    suspend fun count(): Int = dao.count()

    /** Every stored round, oldest first. */
    suspend fun allAscending(): List<RoundResult> = dao.all().map { it.toDomain() }

    /** Restores rounds in bulk; periods that already exist are skipped. Returns how many were new. */
    suspend fun insertAllNew(results: List<RoundResult>): Int =
        dao.insertAll(results.map { it.toEntity() }).count { it != -1L }

    suspend fun clear() = dao.clearAll()
}

class PredictionRepository(private val dao: PredictionDao) {
    suspend fun save(record: PredictionRecordEntity) {
        dao.upsert(record)
    }

    /**
     * Attaches the real result to the prediction that was issued for [period]. Returns that stored
     * record (as it was before resolution), or null when no prediction had been issued for the round.
     */
    suspend fun resolveStored(period: String, actual: BigSmall, verifiedAt: Long = System.currentTimeMillis()): PredictionRecordEntity? {
        val record = dao.byPeriod(period) ?: return null
        val side = BigSmall.parse(record.prediction)
        dao.resolve(period, actual.name, side != null && side == actual, verifiedAt)
        return record
    }

    suspend fun latestOutcomes(limit: Int): List<CallOutcome> =
        dao.latestResolved(limit).mapNotNull { it.toOutcome() }.reversed()

    suspend fun outcomesSince(sinceMillis: Long): List<CallOutcome> =
        dao.resolvedSince(sinceMillis).mapNotNull { it.toOutcome() }

    suspend fun clear() = dao.clearAll()
}
