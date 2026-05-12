package com.clearwave.feasibility

import com.clearwave.domain.LineProfile
import com.clearwave.domain.ServiceAddress

// --- Inbound (from customer) ---

data class FeasibilityRequest(
    val address: ServiceAddress,
    val services: List<String> = listOf("VOICE", "BROADBAND"),
)

data class FeasibilityResponse(
    val address: ServiceAddress,
    val serviceable: Boolean,
    val profiles: List<LineProfile>,
)

// --- OpenNetwork (JSON supplier) ---

data class OpenNetworkFeasibilityRequest(
    val postcode: String,
    val services: List<String>,
)

data class OpenNetworkFeasibilityResponse(
    val available: Boolean,
    val profiles: List<OpenNetworkProfile> = emptyList(),
    val reason: String? = null,
)

data class OpenNetworkProfile(
    val type: String,
    val downloadSpeed: Int,
    val uploadSpeed: Int,
    val description: String,
) {
    fun toLineProfile() = LineProfile(type, downloadSpeed, uploadSpeed, description, "OpenNetwork")
}

// FibreVision XML is built/parsed as strings — see FibreVisionClient.
