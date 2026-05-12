package com.clearwave.support

import com.clearwave.stubs.FibreVisionStub
import com.clearwave.stubs.OpenNetworkStub
import dev.kensa.Kensa.konfigure
import dev.kensa.PackageDisplay
import dev.kensa.Tab
import dev.kensa.fixture.FixtureRegistry.registerFixtures
import dev.kensa.outputs.CapturedOutputsRegistry.registerCapturedOutputs
import dev.kensa.sentence.Acronym.Companion.of
import dev.kensa.spring.web.KensaWebAutoConfiguration.Companion.CLIENT_HTTP_INTERCEPTOR_BEAN
import dev.kensa.spring.web.KensaWebAutoConfiguration.Companion.HANDLER_INTERCEPTOR_BEAN
import dev.kensa.withRenderers
import jakarta.annotation.PostConstruct
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.web.client.RestTemplate
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Test-time configuration:
 *
 * - Registers Kensa fixtures and captured outputs.
 * - Configures non-yaml Kensa settings (acronyms, package display root) via [konfigure].
 * - Swaps the auto-config'd `kensaHandlerInterceptor` and `kensaClientHttpRequestInterceptor`
 *   beans for party-aware variants so the captured sequence diagram is rendered in
 *   Clearwave domain terms (Customer → OrderService etc.) rather than the default
 *   Client/Server. The auto-config's `WebMvcConfigurer` and `RestTemplateCustomizer`
 *   pick those named beans up by qualifier — no further wiring needed.
 * - Provides a `@Primary` [RestTemplate] built via [RestTemplateBuilder] so the
 *   auto-config customizer applies the same interceptor to the SUT's outbound calls
 *   to suppliers.
 */
@TestConfiguration(proxyBeanMethods = false)
class ClearwaveTestConfig {

    @PostConstruct
    fun applyStaticKensaConfig() {
        registerFixtures(TelecomsFixtures)
        registerCapturedOutputs(TelecomsCapturedOutputs)
        konfigure {
            packageDisplay = PackageDisplay.HideCommonPackages
            packageDisplayRoot = "com.clearwave"
            autoOpenTab = Tab.SequenceDiagram
            acronyms(
                of("FTTP", "Fibre to the Premises"),
                of("FTTC", "Fibre to the Cabinet"),
                of("CLI", "Calling Line Identity"),
                of("SLA", "Service Level Agreement"),
                of("PSTN", "Public Switched Telephone Network"),
            )
            withRenderers {
                interactionRenderer(RequestRenderer)
                interactionRenderer(ResponseRenderer)
            }
        }
    }

    @Bean(HANDLER_INTERCEPTOR_BEAN)
    fun telecomsHandlerInterceptor(): HandlerInterceptor = TelecomsHandlerInterceptor()

    @Bean(CLIENT_HTTP_INTERCEPTOR_BEAN)
    fun telecomsClientHttpRequestInterceptor(): ClientHttpRequestInterceptor = TelecomsClientHttpRequestInterceptor()

    @Bean
    @Primary
    fun clearwaveRestTemplate(builder: RestTemplateBuilder): RestTemplate = builder.build()
}

/**
 * Holds the per-suite Spring stub apps. Stubs boot once on first access and shut down
 * via a JVM shutdown hook so a single Gradle test JVM keeps the same supplier ports
 * for every test class.
 */
object ClearwaveStubs {

    val openNetworkStub: OpenNetworkStub
    val fibreVisionStub: FibreVisionStub

    init {
        openNetworkStub = OpenNetworkStub().start()
        fibreVisionStub = FibreVisionStub().start()
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { openNetworkStub.close() }
            runCatching { fibreVisionStub.close() }
        })
    }
}
