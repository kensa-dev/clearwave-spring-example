package com.clearwave.domain

/**
 * An available line profile returned by a supplier during feasibility checking.
 *
 * @param type         Technology type, e.g. FTTP, FTTC, ADSL
 * @param downloadSpeed Download speed in Mbps
 * @param uploadSpeed   Upload speed in Mbps
 * @param description  Human-readable product name
 * @param supplier     Which supplier offers this profile
 */
data class LineProfile(
    val type: String,
    val downloadSpeed: Int,
    val uploadSpeed: Int,
    val description: String,
    val supplier: String,
)
