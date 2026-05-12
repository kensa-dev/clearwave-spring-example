package com.clearwave.stubs

import com.clearwave.feasibility.OpenNetworkFeasibilityResponse
import com.clearwave.feasibility.OpenNetworkProfile
import com.clearwave.order.OpenNetworkNotification
import com.clearwave.order.OpenNetworkOrderResponse
import com.clearwave.support.TelecomsParty
import com.clearwave.support.TrackingId
import com.clearwave.support.TrackingRegistry
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.kensa.spring.web.HttpCapturedRequest
import dev.kensa.spring.web.HttpCapturedResponse
import dev.kensa.state.CapturedInteractionBuilder.Companion.from
import dev.kensa.state.CapturedInteractions
import org.springframework.boot.SpringApplication
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.web.context.ConfigurableWebServerApplicationContext
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestTemplate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Test stub for the OpenNetwork supplier (JSON API). Boots as its own Spring Boot
 * application on a random port.
 *
 * Tests prime scenarios per [TrackingId] via [primeFeasibility] / [primeOrder] and
 * register their [CapturedInteractions] via [register] before the `whenever` action
 * — the stub controller looks up the matching interactions by `X-Tracking-Id` header
 * and records the inbound/outbound sequence diagram arrows automatically, regardless
 * of which Tomcat thread is handling the request.
 */
class OpenNetworkStub : AutoCloseable {

    private val state = OpenNetworkStubState()
    private val registry = TrackingRegistry()
    private lateinit var context: ConfigurableWebServerApplicationContext

    val port: Int get() = context.webServer.port

    fun start(): OpenNetworkStub = apply {
        val app = SpringApplication(OpenNetworkStubApplication::class.java)
        app.setDefaultProperties(mapOf("server.port" to "0", "spring.main.banner-mode" to "off"))
        app.addInitializers(ApplicationContextInitializer<ConfigurableApplicationContext> { ctx ->
            ctx.beanFactory.registerSingleton("openNetworkStubState", state)
            ctx.beanFactory.registerSingleton("openNetworkStubRegistry", registry)
        })
        context = app.run() as ConfigurableWebServerApplicationContext
    }

    override fun close() {
        if (this::context.isInitialized) context.close()
    }

    // --- Registration ---

    fun register(trackingId: TrackingId, interactions: CapturedInteractions) =
        registry.register(trackingId, interactions)

    fun unregister(trackingId: TrackingId) = registry.unregister(trackingId)

    // --- Priming ---

    fun primeFeasibility(trackingId: TrackingId, scenario: FeasibilityScenario) {
        state.feasibilityScenarios[trackingId.toString()] = scenario
    }

    fun primeOrder(trackingId: TrackingId, scenario: OrderScenario) {
        state.orderScenarios[trackingId.toString()] = scenario
    }

    fun feasibilityRequestFor(trackingId: TrackingId): String? = state.feasibilityRequests[trackingId.toString()]
    fun feasibilityResponseFor(trackingId: TrackingId): String? = state.feasibilityResponses[trackingId.toString()]
    fun orderRequestFor(trackingId: TrackingId): String? = state.orderRequests[trackingId.toString()]
    fun orderResponseFor(trackingId: TrackingId): String? = state.orderResponses[trackingId.toString()]
}

internal class OpenNetworkStubState {
    val feasibilityScenarios = ConcurrentHashMap<String, FeasibilityScenario>()
    val feasibilityRequests = ConcurrentHashMap<String, String>()
    val feasibilityResponses = ConcurrentHashMap<String, String>()
    val orderScenarios = ConcurrentHashMap<String, OrderScenario>()
    val orderRequests = ConcurrentHashMap<String, String>()
    val orderResponses = ConcurrentHashMap<String, String>()
    val orderCounter = AtomicInteger(0)
}

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(OpenNetworkStubController::class)
internal class OpenNetworkStubApplication {

    @Bean
    fun openNetworkStubRestTemplate(): RestTemplate = RestTemplate()
}

@RestController
internal class OpenNetworkStubController(
    private val state: OpenNetworkStubState,
    private val registry: TrackingRegistry,
    private val restTemplate: RestTemplate,
) {
    private val mapper = jacksonObjectMapper()

    @PostMapping("/api/feasibility/check", consumes = ["application/json"], produces = ["application/json"])
    fun feasibility(
        @RequestHeader("X-Tracking-Id") trackingId: String,
        @RequestBody body: String,
    ): ResponseEntity<String> {
        state.feasibilityRequests[trackingId] = body
        val interactions = registry.forTrackingId(trackingId)
        captureRequest(interactions, TelecomsParty.FeasibilityService, "/api/feasibility/check", body, MediaType.APPLICATION_JSON_VALUE)

        val (status, responseBody) = when (val scenario = state.feasibilityScenarios[trackingId]) {
            null -> 500 to """{"error":"no scenario primed"}"""
            is FeasibilityScenario.Serviceable -> 200 to mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                OpenNetworkFeasibilityResponse(
                    available = true,
                    profiles = scenario.profiles.map {
                        OpenNetworkProfile(it.type, it.downloadSpeed, it.uploadSpeed, it.description)
                    },
                ),
            )
            FeasibilityScenario.NotServiceable -> 200 to mapper.writeValueAsString(
                OpenNetworkFeasibilityResponse(available = false, reason = "No coverage at this postcode"),
            )
            FeasibilityScenario.SupplierError -> 500 to """{"error":"OpenNetwork system unavailable"}"""
        }
        state.feasibilityResponses[trackingId] = responseBody
        captureResponse(interactions, TelecomsParty.FeasibilityService, status, responseBody, MediaType.APPLICATION_JSON_VALUE)
        return responseFor(status, responseBody, MediaType.APPLICATION_JSON_VALUE)
    }

    @PostMapping("/api/orders", consumes = ["application/json"], produces = ["application/json"])
    fun placeOrder(
        @RequestHeader("X-Tracking-Id") trackingId: String,
        @RequestBody body: String,
    ): ResponseEntity<String> {
        state.orderRequests[trackingId] = body
        val interactions = registry.forTrackingId(trackingId)
        captureRequest(interactions, TelecomsParty.OrderService, "/api/orders", body, MediaType.APPLICATION_JSON_VALUE)

        val scenario = state.orderScenarios[trackingId]
        if (scenario == null) {
            val errBody = """{"error":"no scenario primed"}"""
            captureResponse(interactions, TelecomsParty.OrderService, 500, errBody, MediaType.APPLICATION_JSON_VALUE)
            return ResponseEntity.internalServerError().body(errBody)
        }
        val orderRef = "ON-${state.orderCounter.incrementAndGet().toString().padStart(5, '0')}"
        val responseBody = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            OpenNetworkOrderResponse(orderRef = orderRef, status = "PENDING"),
        )
        state.orderResponses[trackingId] = responseBody
        captureResponse(interactions, TelecomsParty.OrderService, 200, responseBody, MediaType.APPLICATION_JSON_VALUE)

        val callbackUrl = runCatching { mapper.readTree(body).get("notificationCallbackUrl")?.asText() }.getOrNull()
        if (callbackUrl != null) fireNotificationsAsync(orderRef, trackingId, callbackUrl, scenario)

        return ResponseEntity.ok().header("Content-Type", "application/json").body(responseBody)
    }

    private fun fireNotificationsAsync(
        orderRef: String,
        trackingId: String,
        callbackUrl: String,
        scenario: OrderScenario,
    ) {
        val (statuses, delayMs) = when (scenario) {
            is OrderScenario.Accepted -> scenario.notifications.map { it.name } to scenario.notificationDelayMs
            OrderScenario.Rejected -> listOf("REJECTED") to 50L
        }
        thread(isDaemon = true, name = "on-notify-$orderRef") {
            for (status in statuses) {
                Thread.sleep(delayMs)
                val notification = OpenNetworkNotification(orderRef = orderRef, status = status)
                val notificationBody = mapper.writeValueAsString(notification)
                val interactions = registry.forTrackingId(trackingId)
                captureNotification(interactions, callbackUrl, notificationBody)

                val headers = HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    set("X-Tracking-Id", trackingId)
                }
                try {
                    restTemplate.postForEntity(callbackUrl, HttpEntity(notification, headers), Void::class.java)
                } catch (_: Exception) { /* test torn down */ }
            }
        }
    }

    private fun captureRequest(
        interactions: CapturedInteractions,
        targetService: TelecomsParty,
        uri: String,
        body: String,
        contentType: String,
    ) {
        from(targetService)
            .to(TelecomsParty.OpenNetwork)
            .with(
                HttpCapturedRequest(
                    method = "POST",
                    uri = uri,
                    headers = mapOf("Content-Type" to listOf(contentType)),
                    body = body,
                ),
                "HTTP POST $uri",
            )
            .applyTo(interactions)
    }

    private fun captureResponse(
        interactions: CapturedInteractions,
        targetService: TelecomsParty,
        status: Int,
        body: String,
        contentType: String,
    ) {
        from(TelecomsParty.OpenNetwork)
            .to(targetService)
            .with(
                HttpCapturedResponse(
                    status = status,
                    headers = mapOf("Content-Type" to listOf(contentType)),
                    body = body,
                ),
                "HTTP $status",
            )
            .applyTo(interactions)
    }

    private fun captureNotification(
        interactions: CapturedInteractions,
        callbackUrl: String,
        body: String,
    ) {
        from(TelecomsParty.OpenNetwork)
            .to(TelecomsParty.OrderService)
            .with(
                HttpCapturedRequest(
                    method = "POST",
                    uri = callbackUrl,
                    headers = mapOf("Content-Type" to listOf(MediaType.APPLICATION_JSON_VALUE)),
                    body = body,
                ),
                "HTTP POST $callbackUrl",
            )
            .applyTo(interactions)
    }

    private fun responseFor(status: Int, body: String, contentType: String): ResponseEntity<String> {
        val builder = if (status == 200) ResponseEntity.ok() else ResponseEntity.internalServerError()
        return builder.header("Content-Type", contentType).body(body)
    }
}
