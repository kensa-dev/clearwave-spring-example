package com.clearwave

import com.clearwave.order.NotificationStatus
import com.clearwave.order.NotificationStatus.ACKNOWLEDGED
import com.clearwave.order.NotificationStatus.COMMITTED
import com.clearwave.order.NotificationStatus.COMPLETED
import com.clearwave.order.NotificationStatus.DELAYED
import com.clearwave.order.NotificationStatus.REJECTED
import com.clearwave.order.OrderRequest
import com.clearwave.order.OrderResponse
import com.clearwave.order.SupplierNotification
import com.clearwave.stubs.OrderScenario
import com.clearwave.support.ClearwaveSpringTest
import com.clearwave.support.ClearwaveStubs.fibreVisionStub
import com.clearwave.support.ClearwaveStubs.openNetworkStub
import com.clearwave.support.TelecomsCapturedOutputs.OrderConfirmation
import com.clearwave.support.TelecomsFixtures.appointmentSlot
import com.clearwave.support.TelecomsFixtures.broadbandProfile
import com.clearwave.support.TelecomsFixtures.broadbandSupplier
import com.clearwave.support.TelecomsFixtures.customerId
import com.clearwave.support.TelecomsFixtures.serviceAddress
import com.clearwave.support.TelecomsFixtures.trackingId
import com.clearwave.support.TelecomsFixtures.voiceProfile
import com.clearwave.support.TelecomsFixtures.voiceSupplier
import com.clearwave.support.TrackingId
import dev.kensa.Action
import dev.kensa.ActionContext
import dev.kensa.ExpandableRenderedValue
import dev.kensa.GivensContext
import dev.kensa.Notes
import dev.kensa.StateCollector
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestTemplate
import kotlin.time.Duration.Companion.seconds

@Notes("""
**Order Service** orchestrates provisioning across two suppliers for a combined voice + broadband package.

Notification lifecycle (per supplier):

| Status | Meaning |
|--------|---------|
| `ACKNOWLEDGED` | Supplier has received and accepted the order |
| `COMMITTED` | Resources reserved; engineer visit booked |
| `DELAYED` | Engineer visit rescheduled |
| `COMPLETED` | Service is live |
| `REJECTED` | Order cannot be fulfilled |

See [FeasibilityServiceTest](#FeasibilityServiceTest) for feasibility pre-checks.
""")
class OrderServiceTest : ClearwaveSpringTest() {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun `voice and broadband order is successfully completed`() {
        given(openNetworkWillCompleteTheOrder())
        and(fibreVisionWillCompleteTheOrder())

        whenever(aVoiceAndBroadbandOrderIsPlaced())

        then(theOrderConfirmation(), shouldBePending())
        thenEventuallyAllNotifications(
            shouldShowBothSuppliersCompletedSuccessfully(
                voiceSupplier = fixtures[voiceSupplier],
                broadbandSupplier = fixtures[broadbandSupplier],
            )
        )
    }

    @Test
    fun `order is delayed then completed`() {
        given(openNetworkWillCompleteTheOrder())
        and(fibreVisionWillDelayThenCompleteTheOrder())

        whenever(aVoiceAndBroadbandOrderIsPlaced())

        then(theOrderConfirmation(), shouldBePending())
        thenEventuallyFibreVisionNotifications(shouldShowLifecycle(
            supplier = fixtures[broadbandSupplier],
            lifecycle = theDelayedLifecycle(),
        ))
    }

    @Test
    fun `order is rejected by fibre vision`() {
        given(openNetworkWillCompleteTheOrder())
        and(fibreVisionWillRejectTheOrder())

        whenever(aVoiceAndBroadbandOrderIsPlaced())

        then(theOrderConfirmation(), shouldBePending())
        thenEventuallyFibreVisionNotifications(shouldBeRejected(
            supplier = fixtures[broadbandSupplier],
        ))
    }

    // --- Givens ---

    private fun openNetworkWillCompleteTheOrder() = Action<GivensContext> { (fixtures) ->
        openNetworkStub.primeOrder(fixtures[trackingId], OrderScenario.completed)
    }

    private fun fibreVisionWillCompleteTheOrder() = Action<GivensContext> { (fixtures) ->
        fibreVisionStub.primeOrder(fixtures[trackingId], OrderScenario.completed)
    }

    private fun fibreVisionWillDelayThenCompleteTheOrder() = Action<GivensContext> { (fixtures) ->
        fibreVisionStub.primeOrder(fixtures[trackingId], OrderScenario.delayed)
    }

    private fun fibreVisionWillRejectTheOrder() = Action<GivensContext> { (fixtures) ->
        fibreVisionStub.primeOrder(fixtures[trackingId], OrderScenario.Rejected)
    }

    // --- Action ---

    private fun aVoiceAndBroadbandOrderIsPlaced() = Action<ActionContext> { (fixtures, interactions) ->
        val tid = fixtures[trackingId]
        openNetworkStub.register(tid, interactions)
        fibreVisionStub.register(tid, interactions)
        val orderRequest = OrderRequest(
            customerId = fixtures[customerId],
            address = fixtures[serviceAddress],
            voiceProfile = fixtures[voiceProfile],
            broadbandProfile = fixtures[broadbandProfile],
            appointmentSlot = fixtures[appointmentSlot],
        )
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set(TrackingId.HEADER, tid.toString())
        }
        val response = restTemplate.exchange(
            "/api/orders",
            HttpMethod.POST,
            HttpEntity(orderRequest, headers),
            OrderResponse::class.java,
        )
        outputs[OrderConfirmation] = response.body!!
        interactions.captureTimePassing("Awaiting supplier notifications")
    }

    // --- State Collectors and async helpers ---

    private fun theOrderConfirmation() = StateCollector { outputs[OrderConfirmation] }

    private fun allNotifications() = StateCollector {
        orderNotificationsFor(fixtures[trackingId].toString())
    }

    private fun fibreVisionNotifications() = StateCollector {
        orderNotificationsFor(fixtures[trackingId].toString()).filter { it.supplier == "FibreVision" }
    }

    private fun thenEventuallyAllNotifications(matcher: Matcher<List<SupplierNotification>>) =
        thenEventually(10.seconds, allNotifications(), matcher)

    private fun thenEventuallyFibreVisionNotifications(matcher: Matcher<List<SupplierNotification>>) =
        thenEventually(10.seconds, fibreVisionNotifications(), matcher)

    @Autowired
    lateinit var orderService: com.clearwave.order.OrderService

    private fun orderNotificationsFor(trackingId: String): List<SupplierNotification> =
        orderService.notificationsFor(trackingId)

    // --- Assertions ---

    private fun shouldBePending() = Matcher<OrderResponse> { result ->
        MatcherResult(
            result.status == "PENDING",
            { "Expected order status PENDING but was ${result.status}" },
            { "Expected order status not to be PENDING" }
        )
    }

    private fun shouldShowBothSuppliersCompletedSuccessfully(
        voiceSupplier: String,
        broadbandSupplier: String,
    ) = Matcher<List<SupplierNotification>> { notifications ->
        val completedLifecycle = listOf(ACKNOWLEDGED, COMMITTED, COMPLETED)
        val voiceStatuses = notifications.filter { it.supplier == voiceSupplier }.map { it.status }
        val broadbandStatuses = notifications.filter { it.supplier == broadbandSupplier }.map { it.status }
        MatcherResult(
            notifications.size == 6 && voiceStatuses == completedLifecycle && broadbandStatuses == completedLifecycle,
            { "Expected both suppliers to complete: ${voiceSupplier}=$voiceStatuses, ${broadbandSupplier}=$broadbandStatuses" },
            { "Expected not both suppliers to complete" }
        )
    }

    @ExpandableRenderedValue
    private fun theDelayedLifecycle(): List<NotificationStatus> =
        listOf(ACKNOWLEDGED, COMMITTED, DELAYED, COMPLETED)

    private fun shouldShowLifecycle(
        supplier: String,
        lifecycle: List<NotificationStatus>,
    ) = Matcher<List<SupplierNotification>> { notifications ->
        val actual = notifications.map { it.status }
        MatcherResult(
            actual == lifecycle,
            { "Expected $supplier to show ${lifecycle.joinToString(" → ")} but got $actual" },
            { "Expected not to show $lifecycle lifecycle" }
        )
    }

    private fun shouldBeRejected(supplier: String) = Matcher<List<SupplierNotification>> { notifications ->
        MatcherResult(
            notifications.size == 1 && notifications.single().status == REJECTED,
            { "Expected a single REJECTED notification from ${supplier} but got $notifications" },
            { "Expected not to be rejected" }
        )
    }
}
