package com.clearwave.domain

import java.time.LocalDate

/**
 * An engineer visit slot required when the selected profile needs physical installation.
 */
data class AppointmentSlot(
    val date: LocalDate,
    val timeSlot: String,   // "AM" or "PM"
)
