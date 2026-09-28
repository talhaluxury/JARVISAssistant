package com.jarvis.assistant.quotex.analysis

import com.jarvis.assistant.trading.QuotexDecision

enum class SignalState { SCANNING, WATCHLIST, PRE_CONFIRMATION, CONFIRMED_SETUP, EXPIRED, INVALIDATED, NO_TRADE }

data class SignalStateReading(val state: SignalState, val direction: QuotexDecision, val candlesInState: Int)

/**
 * Turns a stream of [ConfluenceResult]s into a state, so a setup is never displayed as if it were still
 * fresh: SCANNING never jumps straight to CONFIRMED_SETUP, a flip in direction INVALIDATES the setup, and a
 * setup that has sat unresolved too long EXPIREs rather than staying on screen forever.
 */
class SignalStateMachine(private val maxCandlesConfirmed: Int = 6) {
    private var state = SignalState.SCANNING
    private var direction = QuotexDecision.WAIT
    private var candlesInState = 0

    fun update(confluence: ConfluenceResult, dataQualityOk: Boolean): SignalStateReading {
        if (!dataQualityOk) {
            transition(SignalState.NO_TRADE, QuotexDecision.WAIT)
            return current()
        }

        val sameDirection = confluence.direction == direction && direction != QuotexDecision.WAIT

        when (confluence.quality) {
            SetupQuality.NO_SETUP, SetupQuality.WEAK_SETUP -> transition(SignalState.SCANNING, QuotexDecision.WAIT)

            SetupQuality.WATCH -> {
                if (state == SignalState.WATCHLIST && sameDirection) {
                    candlesInState++
                } else {
                    transition(SignalState.WATCHLIST, confluence.direction)
                }
            }

            SetupQuality.SETUP_DETECTED, SetupQuality.HIGH_CONFLUENCE_SETUP -> {
                when {
                    !sameDirection -> {
                        // A fresh direction, or a flip while something else was active - invalidate first.
                        if (direction != QuotexDecision.WAIT && direction != confluence.direction && state in ACTIVE_STATES) {
                            transition(SignalState.INVALIDATED, confluence.direction)
                        } else {
                            transition(SignalState.PRE_CONFIRMATION, confluence.direction)
                        }
                    }
                    state == SignalState.PRE_CONFIRMATION -> transition(SignalState.CONFIRMED_SETUP, confluence.direction)
                    state == SignalState.CONFIRMED_SETUP -> {
                        if (candlesInState >= maxCandlesConfirmed) {
                            transition(SignalState.EXPIRED, confluence.direction)
                        } else {
                            candlesInState++
                        }
                    }
                    else -> transition(SignalState.PRE_CONFIRMATION, confluence.direction)
                }
            }
        }
        return current()
    }

    private fun transition(newState: SignalState, newDirection: QuotexDecision) {
        state = newState
        direction = newDirection
        candlesInState = 0
    }

    private fun current() = SignalStateReading(state, direction, candlesInState)

    companion object {
        private val ACTIVE_STATES = setOf(SignalState.WATCHLIST, SignalState.PRE_CONFIRMATION, SignalState.CONFIRMED_SETUP)
    }
}
