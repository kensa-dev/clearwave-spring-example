package com.clearwave.stubs

import com.clearwave.feasibility.OpenNetworkFeasibilityResponse
import com.clearwave.feasibility.OpenNetworkProfile
import com.clearwave.order.OpenNetworkNotification
import com.clearwave.order.OpenNetworkOrderResponse
import com.clearwave.support.TrackingId
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
 * application on a random port. Tests prime scenarios per [TrackingId] via [primeFeasibility]
 * and [primeOrder]; the captured requests are accessible via [feasibilityRequestFor] etc.
 */
class OpenNetworkStub : AutoCloseable {

    private val state = OpenNetworkStubState()
    private lateinit var context: ConfigurableWebServerApplicationContext

    val port: Int get() = context.webServer.port

    fun start(): OpenNetworkStub = apply {
        val app = SpringApplication(OpenNetworkStubApplication::class.java)
        app.setDefaultProperties(mapOf("server.port" to "0", "spring.main.banner-mode" to "off"))
        app.addInitializers(ApplicationContextInitializer<ConfigurableApplicationContext> { ctx ->
            ctx.beanFactory.registerSingleton("openNetworkStubState", state)
        })
        context = app.run() as ConfigurableWebServerApplicationContext
    }

    override fun close() {
        if (this::context.isInitialized) context.close()
    }

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
    private val restTemplate: RestTemplate,
) {
    private val mapper = jacksonObjectMapper()

    @PostMapping("/api/feasibility/check", consumes = ["application/json"], produces = ["application/json"])
    fun feasibility(
        @RequestHeader("X-Tracking-Id") trackingId: String,
        @RequestBody body: String,
    ): ResponseEntity<String> {
        state.feasibilityRequests[trackingId] = body
        val scenario = state.feasibilityScenarios[trackingId]
            ?: return ResponseEntity.internalServerError().body("""{"error":"no scenario primed"}""")
        return when (scenario) {
            is FeasibilityScenario.Serviceable -> {
                val responseBody = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    OpenNetworkFeasibilityResponse(
                        available = true,
                        profiles = scenario.profiles.map {
                            OpenNetworkProfile(it.type, it.downloadSpeed, it.uploadSpeed, it.description)
                        },
                    ),
                )
                state.feasibilityResponses[trackingId] = responseBody
                ResponseEntity.ok().header("Content-Type", "application/json").body(responseBody)
            }
            FeasibilityScenario.NotServiceable -> {
                val responseBody = mapper.writeValueAsString(
                    OpenNetworkFeasibilityResponse(available = false, reason = "No coverage at this postcode"),
                )
                state.feasibilityResponses[trackingId] = responseBody
                ResponseEntity.ok().header("Content-Type", "application/json").body(responseBody)
            }
            FeasibilityScenario.SupplierError -> {
                val responseBody = """{"error":"OpenNetwork system unavailable"}"""
                state.feasibilityResponses[trackingId] = responseBody
                ResponseEntity.internalServerError().header("Content-Type", "application/json").body(responseBody)
            }
        }
    }

    @PostMapping("/api/orders", consumes = ["application/json"], produces = ["application/json"])
    fun placeOrder(
        @RequestHeader("X-Tracking-Id") trackingId: String,
        @RequestBody body: String,
    ): ResponseEntity<String> {
        state.orderRequests[trackingId] = body
        val scenario = state.orderScenarios[trackingId]
            ?: return ResponseEntity.internalServerError().body("""{"error":"no scenario primed"}""")
        val orderRef = "ON-${state.orderCounter.incrementAndGet().toString().padStart(5, '0')}"
        val responseBody = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            OpenNetworkOrderResponse(orderRef = orderRef, status = "PENDING"),
        )
        state.orderResponses[trackingId] = responseBody

        val callbackUrl = try {
            mapper.readTree(body).get("notificationCallbackUrl")?.asText()
        } catch (_: Exception) {
            null
        }
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
}
