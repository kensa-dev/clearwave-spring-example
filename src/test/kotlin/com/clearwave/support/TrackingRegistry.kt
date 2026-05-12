package com.clearwave.support

import dev.kensa.state.CapturedInteractions
import dev.kensa.state.SetupStrategy
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes [CapturedInteractions] to the correct test invocation by tracking ID.
 *
 * Each stub holds one registry. Before each `whenever` action the test registers its
 * [TrackingId] → [CapturedInteractions] mapping. When the stub's HTTP handler receives
 * a request it extracts the `X-Tracking-Id` header and looks up the matching interactions
 * — enabling safe parallel test execution *and* cross-thread capture (the stub runs on
 * its own Tomcat thread but reads the header off the wire rather than relying on a
 * thread-local).
 */
class TrackingRegistry {

    private val map = ConcurrentHashMap<String, CapturedInteractions>()

    fun register(trackingId: TrackingId, interactions: CapturedInteractions) {
        map[trackingId.value.toString()] = interactions
    }

    fun unregister(trackingId: TrackingId) {
        map.remove(trackingId.value.toString())
    }

    fun forTrackingId(trackingId: String): CapturedInteractions =
        map[trackingId] ?: CapturedInteractions(SetupStrategy.Ignored)
}
