package com.clearwave.order

import com.clearwave.domain.AppointmentSlot
import com.clearwave.domain.LineProfile
import com.clearwave.domain.ServiceAddress

// --- Inbound (from customer) ---

data class OrderRequest(
    val customerId: String,
    val address: ServiceAddress,
    val voiceProfile: LineProfile?,
    val broadbandProfile: LineProfile?,
    val appointmentSlot: AppointmentSlot?,
)

data class OrderResponse(
    val orderId: String,
    val status: String,                     // PENDING
    val openNetworkRef: String? = null,
    val fibreVisionRef: String? = null,
)

// --- Notification (received async from supplier) ---

enum class NotificationStatus {
    ACKNOWLEDGED, COMMITTED, DELAYED, COMPLETED, REJECTED
}

data class SupplierNotification(
    val supplierRef: String,
    val supplier: String,                   // "OpenNetwork" | "FibreVision"
    val status: NotificationStatus,
    val message: String? = null,
)

// --- OpenNetwork order (JSON) ---

data class OpenNetworkOrderRequest(
    val postcode: String,
    val profileType: String,
    val downloadSpeed: Int,
    val appointmentDate: String?,
    val appointmentSlot: String?,
    val notificationCallbackUrl: String,
)

data class OpenNetworkOrderResponse(
    val orderRef: String,
    val status: String,                     // PENDING
)

data class OpenNetworkNotification(
    val orderRef: String,
    val status: String,
    val message: String? = null,
)
