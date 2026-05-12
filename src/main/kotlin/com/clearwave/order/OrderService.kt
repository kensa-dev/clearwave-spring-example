package com.clearwave.order

import com.clearwave.SupplierProperties
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Clearwave order service. Places telecom service orders with suppliers and
 * receives asynchronous status notifications via the OrderController callback.
 */
@Service
class OrderService(
    private val restTemplate: RestTemplate,
    private val suppliers: SupplierProperties,
) {

    private val notifications = ConcurrentHashMap<String, MutableList<SupplierNotification>>()

    fun notificationsFor(trackingId: String): List<SupplierNotification> =
        notifications[trackingId].orEmpty().toList()

    fun recordNotification(trackingId: String, notification: SupplierNotification) {
        notifications.getOrPut(trackingId) { mutableListOf() }.add(notification)
    }

    fun placeOrder(trackingId: String, callbackBaseUrl: String, request: OrderRequest): OrderResponse {
        val orderId = "CW-${UUID.randomUUID().toString().take(8).uppercase()}"
        notifications[trackingId] = mutableListOf()
        val callbackUrl = "$callbackBaseUrl/api/notifications/$trackingId"

        val openNetworkRef = request.voiceProfile?.let { placeOpenNetworkOrder(trackingId, request, it, callbackUrl) }
        val fibreVisionRef = request.broadbandProfile?.let { placeFibreVisionOrder(trackingId, request, it, callbackUrl) }

        return OrderResponse(
            orderId = orderId,
            status = "PENDING",
            openNetworkRef = openNetworkRef,
            fibreVisionRef = fibreVisionRef,
        )
    }

    private fun placeOpenNetworkOrder(
        trackingId: String,
        req: OrderRequest,
        profile: com.clearwave.domain.LineProfile,
        callbackUrl: String,
    ): String? {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Tracking-Id", trackingId)
        }
        val body = OpenNetworkOrderRequest(
            postcode = req.address.postcode,
            profileType = profile.type,
            downloadSpeed = profile.downloadSpeed,
            appointmentDate = req.appointmentSlot?.date?.toString(),
            appointmentSlot = req.appointmentSlot?.timeSlot,
            notificationCallbackUrl = callbackUrl,
        )
        return try {
            restTemplate.exchange(
                "${suppliers.openNetworkUrl}/api/orders",
                HttpMethod.POST,
                HttpEntity(body, headers),
                OpenNetworkOrderResponse::class.java,
            ).body?.orderRef
        } catch (_: RestClientException) {
            null
        }
    }

    private fun placeFibreVisionOrder(
        trackingId: String,
        req: OrderRequest,
        profile: com.clearwave.domain.LineProfile,
        callbackUrl: String,
    ): String? {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<ServiceOrder>
    <Postcode>${req.address.postcode}</Postcode>
    <AddressLine1>${req.address.addressLine1}</AddressLine1>
    <Town>${req.address.town}</Town>
    <County>${req.address.county}</County>
    <ProfileType>${profile.type}</ProfileType>
    <DownloadSpeed>${profile.downloadSpeed}</DownloadSpeed>
    <AppointmentDate>${req.appointmentSlot?.date ?: ""}</AppointmentDate>
    <AppointmentSlot>${req.appointmentSlot?.timeSlot ?: ""}</AppointmentSlot>
    <NotificationCallbackUrl>$callbackUrl</NotificationCallbackUrl>
</ServiceOrder>"""
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_XML
            set("X-Tracking-Id", trackingId)
        }
        return try {
            val response = restTemplate.exchange(
                "${suppliers.fibreVisionUrl}/api/orders",
                HttpMethod.POST,
                HttpEntity(xml, headers),
                String::class.java,
            )
            response.body?.extractXml("OrderRef")
        } catch (_: RestClientException) {
            null
        }
    }

    private fun String.extractXml(tag: String): String? =
        Regex("<$tag>(.*?)</$tag>").find(this)?.groupValues?.getOrNull(1)
}
