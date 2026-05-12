package com.clearwave.stubs

import com.clearwave.domain.LineProfile

/**
 * Primed scenario for feasibility stub responses.
 */
sealed class FeasibilityScenario {
    data class Serviceable(val profiles: List<LineProfile>) : FeasibilityScenario()
    data object NotServiceable : FeasibilityScenario()
    data object SupplierError : FeasibilityScenario()
}
