package com.clearwave.stubs

import com.clearwave.order.NotificationStatus

sealed class OrderScenario {

    data class Accepted(
        val notifications: List<NotificationStatus>,
        val notificationDelayMs: Long = 50L,
    ) : OrderScenario()

    data object Rejected : OrderScenario()

    companion object {
        val completed = Accepted(
            listOf(
                NotificationStatus.ACKNOWLEDGED,
                NotificationStatus.COMMITTED,
                NotificationStatus.COMPLETED,
            )
        )

        val delayed = Accepted(
            listOf(
                NotificationStatus.ACKNOWLEDGED,
                NotificationStatus.COMMITTED,
                NotificationStatus.DELAYED,
                NotificationStatus.COMPLETED,
            )
        )
    }
}
