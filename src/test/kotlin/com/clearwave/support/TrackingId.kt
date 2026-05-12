package com.clearwave.support

import java.util.UUID

/**
 * A per-test correlation token sent as `X-Tracking-Id` on every outbound HTTP request.
 *
 * Stubs use this to route interactions to the correct test invocation and to record
 * primed scenarios and captured exchanges — making parallel test execution safe.
 */
data class TrackingId(val value: UUID = UUID.randomUUID()) {
    override fun toString(): String = value.toString()

    companion object {
        const val HEADER = "X-Tracking-Id"
    }
}
