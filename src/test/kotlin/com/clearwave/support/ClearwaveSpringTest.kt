package com.clearwave.support

import dev.kensa.Kensa.konfigure
import dev.kensa.UseSetupStrategy
import dev.kensa.junit.KensaExtension
import dev.kensa.junit.KensaTest
import dev.kensa.kotest.WithKotest
import dev.kensa.spring.KensaSpringExtension
import dev.kensa.state.SetupStrategy
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.io.path.Path

/**
 * Base class for Clearwave Spring acceptance tests.
 *
 * Boots the SUT as a real Spring Boot application on a random port and brings up the
 * supplier stubs (once per JVM via [ClearwaveStubs]). [supplierProperties] wires the
 * stub ports into Spring's environment so the SUT's [com.clearwave.SupplierProperties]
 * point at the live stubs.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ExtendWith(KensaSpringExtension::class, KensaExtension::class)
@ContextConfiguration(classes = [com.clearwave.ClearwaveApplication::class, ClearwaveTestConfig::class])
@UseSetupStrategy(SetupStrategy.Grouped)
abstract class ClearwaveSpringTest : KensaTest, WithKotest {

    companion object {
        init {
            konfigure {
                outputDir = Path("build/kensa-output")
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun supplierProperties(registry: DynamicPropertyRegistry) {
            registry.add("suppliers.open-network-url") { "http://localhost:${ClearwaveStubs.openNetworkStub.port}" }
            registry.add("suppliers.fibre-vision-url") { "http://localhost:${ClearwaveStubs.fibreVisionStub.port}" }
        }
    }
}
