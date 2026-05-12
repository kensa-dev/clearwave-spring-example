package com.clearwave.order

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class OrderController(private val service: OrderService) {

    @PostMapping("/api/orders")
    fun placeOrder(
        @RequestHeader("X-Tracking-Id") trackingId: String,
        @RequestBody request: OrderRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<OrderResponse> {
        val callbackBaseUrl = "${servletRequest.scheme}://${servletRequest.serverName}:${servletRequest.serverPort}"
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.placeOrder(trackingId, callbackBaseUrl, request))
    }

    @PostMapping(
        path = ["/api/notifications/{trackingId}"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun jsonNotification(
        @PathVariable trackingId: String,
        @RequestBody body: OpenNetworkNotification,
    ) {
        service.recordNotification(
            trackingId,
            SupplierNotification(
                supplierRef = body.orderRef,
                supplier = "OpenNetwork",
                status = NotificationStatus.valueOf(body.status),
                message = body.message,
            ),
        )
    }

    @PostMapping(
        path = ["/api/notifications/{trackingId}"],
        consumes = [MediaType.APPLICATION_XML_VALUE],
    )
    fun xmlNotification(
        @PathVariable trackingId: String,
        @RequestBody body: String,
    ) {
        val supplierRef = body.extractXml("OrderRef") ?: return
        val status = body.extractXml("Status") ?: return
        service.recordNotification(
            trackingId,
            SupplierNotification(
                supplierRef = supplierRef,
                supplier = "FibreVision",
                status = NotificationStatus.valueOf(status),
                message = body.extractXml("Message"),
            ),
        )
    }

    private fun String.extractXml(tag: String): String? =
        Regex("<$tag>(.*?)</$tag>").find(this)?.groupValues?.getOrNull(1)
}
