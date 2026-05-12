package com.clearwave.stubs

import com.clearwave.support.TrackingId
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
 * application on a random port. Mirrors [OpenNetworkStub] but speaks XML.
 */
class FibreVisionStub : AutoCloseable {

    private val state = FibreVisionStubState()
    private lateinit var context: ConfigurableWebServerApplicationContext

    val port: Int get() = context.webServer.port

    fun start(): FibreVisionStub = apply {
        val app = SpringApplication(FibreVisionStubApplication::class.java)
        app.setDefaultProperties(mapOf("server.port" to "0", "spring.main.banner-mode" to "off"))
        app.addInitializers(ApplicationContextInitializer<ConfigurableApplicationContext> { ctx ->
            ctx.beanFactory.registerSingleton("fibreVisionStubState", state)
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
    private val restTemplate: RestTemplate,
) {

    @PostMapping("/api/feasibility/enquiry", consumes = ["application/xml"], produces = ["application/xml"])
    fun feasibility(
        @RequestHeader("X-Tracking-Id") trackingId: String,
        @RequestBody body: String,
    ): ResponseEntity<String> {
        state.feasibilityRequests[trackingId] = body
        val scenario = state.feasibilityScenarios[trackingId]
            ?: return ResponseEntity.internalServerError().body("<Error><Message>no scenario primed</Message></Error>")
        val responseBody = when (scenario) {
            is FeasibilityScenario.Serviceable -> buildServiceable(scenario)
            FeasibilityScenario.NotServiceable -> """<?xml version="1.0" encoding="UTF-8"?>
<FeasibilityResponse>
    <Status>NOT_SERVICEABLE</Status>
    <Reason>No infrastructure available at this postcode</Reason>
</FeasibilityResponse>"""
            FeasibilityScenario.SupplierError -> """<?xml version="1.0" encoding="UTF-8"?>
<Error><Message>FibreVision system unavailable</Message></Error>"""
        }
        state.feasibilityResponses[trackingId] = responseBody
        return if (scenario == FeasibilityScenario.SupplierError) {
            ResponseEntity.internalServerError().header("Content-Type", "application/xml").body(responseBody)
        } else {
            ResponseEntity.ok().header("Content-Type", "application/xml").body(responseBody)
        }
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
        val scenario = state.orderScenarios[trackingId]
            ?: return ResponseEntity.internalServerError().body("<Error><Message>no scenario primed</Message></Error>")
        val orderRef = "FV-${state.orderCounter.incrementAndGet().toString().padStart(5, '0')}"
        val responseBody = """<?xml version="1.0" encoding="UTF-8"?>
<OrderResponse>
    <OrderRef>$orderRef</OrderRef>
    <Status>PENDING</Status>
</OrderResponse>"""
        state.orderResponses[trackingId] = responseBody

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

    private fun String.extractXml(tag: String): String? =
        Regex("<$tag>(.*?)</$tag>").find(this)?.groupValues?.getOrNull(1)
}
