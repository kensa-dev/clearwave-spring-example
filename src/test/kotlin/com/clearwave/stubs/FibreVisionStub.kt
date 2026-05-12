package com.clearwave.stubs

import com.clearwave.support.TelecomsParty
import com.clearwave.support.TrackingId
import com.clearwave.support.TrackingRegistry
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
 * Test stub for the FibreVision supplier (XML API). Boots as its own Spring Boot
 * application on a random port. Mirrors [OpenNetworkStub] but speaks XML; uses the
 * same trackingId-based capture pattern via [TrackingRegistry].
 */
class FibreVisionStub : AutoCloseable {

    private val state = FibreVisionStubState()
    private val registry = TrackingRegistry()
    private lateinit var context: ConfigurableWebServerApplicationContext

    val port: Int get() = context.webServer.port

    fun start(): FibreVisionStub = apply {
        val app = SpringApplication(FibreVisionStubApplication::class.java)
        app.setDefaultProperties(mapOf("server.port" to "0", "spring.main.banner-mode" to "off"))
        app.addInitializers(ApplicationContextInitializer<ConfigurableApplicationContext> { ctx ->
            ctx.beanFactory.registerSingleton("fibreVisionStubState", state)
            ctx.beanFactory.registerSingleton("fibreVisionStubRegistry", registry)
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

internal class FibreVisionStubState {
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
@Import(FibreVisionStubController::class)
internal class FibreVisionStubApplication {

    @Bean
    fun fibreVisionStubRestTemplate(): RestTemplate = RestTemplate()
}

@RestController
internal class FibreVisionStubController(
    private val state: FibreVisionStubState,
    private val registry: TrackingRegistry,
    private val restTemplate: RestTemplate,
) {

    @PostMapping("/api/feasibility/enquiry", consumes = ["application/xml"], produces = ["application/xml"])
    fun feasibility(
        @RequestHeader("X-Tracking-Id") trackingId: String,
        @RequestBody body: String,
    ): ResponseEntity<String> {
        state.feasibilityRequests[trackingId] = body
        val interactions = registry.forTrackingId(trackingId)
        captureRequest(interactions, TelecomsParty.FeasibilityService, "/api/feasibility/enquiry", body)

        val scenario = state.feasibilityScenarios[trackingId]
        val (status, responseBody) = when (scenario) {
            null -> 500 to "<Error><Message>no scenario primed</Message></Error>"
            is FeasibilityScenario.Serviceable -> 200 to buildServiceable(scenario)
            FeasibilityScenario.NotServiceable -> 200 to """<?xml version="1.0" encoding="UTF-8"?>
<FeasibilityResponse>
    <Status>NOT_SERVICEABLE</Status>
    <Reason>No infrastructure available at this postcode</Reason>
</FeasibilityResponse>"""
            FeasibilityScenario.SupplierError -> 500 to """<?xml version="1.0" encoding="UTF-8"?>
<Error><Message>FibreVision system unavailable</Message></Error>"""
        }
        state.feasibilityResponses[trackingId] = responseBody
        captureResponse(interactions, TelecomsParty.FeasibilityService, status, responseBody)

        return responseFor(status, responseBody)
    }

    private fun buildServiceable(scenario: FeasibilityScenario.Serviceable): String {
        val profilesXml = scenario.profiles.joinToString("\n") { p ->
            """    <Profile>
        <Type>${p.type}</Type>
        <DownloadSpeed>${p.downloadSpeed}</DownloadSpeed>
        <UploadSpeed>${p.uploadSpeed}</UploadSpeed>
        <Description>${p.description}</Description>
    </Profile>"""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<FeasibilityResponse>
    <Status>SERVICEABLE</Status>
    <Profiles>
$profilesXml
    </Profiles>
</FeasibilityResponse>"""
    }

    @PostMapping("/api/orders", consumes = ["application/xml"], produces = ["application/xml"])
    fun placeOrder(
        @RequestHeader("X-Tracking-Id") trackingId: String,
        @RequestBody body: String,
    ): ResponseEntity<String> {
        state.orderRequests[trackingId] = body
        val interactions = registry.forTrackingId(trackingId)
        captureRequest(interactions, TelecomsParty.OrderService, "/api/orders", body)

        val scenario = state.orderScenarios[trackingId]
        if (scenario == null) {
            val errBody = "<Error><Message>no scenario primed</Message></Error>"
            captureResponse(interactions, TelecomsParty.OrderService, 500, errBody)
            return ResponseEntity.internalServerError().body(errBody)
        }
        val orderRef = "FV-${state.orderCounter.incrementAndGet().toString().padStart(5, '0')}"
        val responseBody = """<?xml version="1.0" encoding="UTF-8"?>
<OrderResponse>
    <OrderRef>$orderRef</OrderRef>
    <Status>PENDING</Status>
</OrderResponse>"""
        state.orderResponses[trackingId] = responseBody
        captureResponse(interactions, TelecomsParty.OrderService, 200, responseBody)

        val callbackUrl = body.extractXml("NotificationCallbackUrl")
        if (callbackUrl != null) fireNotificationsAsync(orderRef, trackingId, callbackUrl, scenario)

        return ResponseEntity.ok().header("Content-Type", "application/xml").body(responseBody)
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
        thread(isDaemon = true, name = "fv-notify-$orderRef") {
            for (status in statuses) {
                Thread.sleep(delayMs)
                val xml = """<?xml version="1.0" encoding="UTF-8"?>
<OrderNotification>
    <OrderRef>$orderRef</OrderRef>
    <Status>$status</Status>
</OrderNotification>"""
                val interactions = registry.forTrackingId(trackingId)
                captureNotification(interactions, callbackUrl, xml)

                val headers = HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_XML
                    set("X-Tracking-Id", trackingId)
                }
                try {
                    restTemplate.postForEntity(callbackUrl, HttpEntity(xml, headers), Void::class.java)
                } catch (_: Exception) { /* test torn down */ }
            }
        }
    }

    private fun captureRequest(
        interactions: CapturedInteractions,
        targetService: TelecomsParty,
        uri: String,
        body: String,
    ) {
        from(targetService)
            .to(TelecomsParty.FibreVision)
            .with(
                HttpCapturedRequest(
                    method = "POST",
                    uri = uri,
                    headers = mapOf("Content-Type" to listOf(MediaType.APPLICATION_XML_VALUE)),
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
    ) {
        from(TelecomsParty.FibreVision)
            .to(targetService)
            .with(
                HttpCapturedResponse(
                    status = status,
                    headers = mapOf("Content-Type" to listOf(MediaType.APPLICATION_XML_VALUE)),
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
        from(TelecomsParty.FibreVision)
            .to(TelecomsParty.OrderService)
            .with(
                HttpCapturedRequest(
                    method = "POST",
                    uri = callbackUrl,
                    headers = mapOf("Content-Type" to listOf(MediaType.APPLICATION_XML_VALUE)),
                    body = body,
                ),
                "HTTP POST $callbackUrl",
            )
            .applyTo(interactions)
    }

    private fun responseFor(status: Int, body: String): ResponseEntity<String> {
        val builder = if (status == 200) ResponseEntity.ok() else ResponseEntity.internalServerError()
        return builder.header("Content-Type", MediaType.APPLICATION_XML_VALUE).body(body)
    }

    private fun String.extractXml(tag: String): String? =
        Regex("<$tag>(.*?)</$tag>").find(this)?.groupValues?.getOrNull(1)
}
