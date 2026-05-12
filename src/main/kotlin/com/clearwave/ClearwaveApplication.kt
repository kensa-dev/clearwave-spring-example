package com.clearwave

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.web.client.RestTemplate

@SpringBootApplication
@ComponentScan(
    basePackages = ["com.clearwave"],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.REGEX, pattern = ["com\\.clearwave\\.stubs\\..*"]),
    ],
)
@EnableConfigurationProperties(SupplierProperties::class)
class ClearwaveApplication {

    @Bean
    fun restTemplate(): RestTemplate = RestTemplate()
}

@ConfigurationProperties("suppliers")
data class SupplierProperties(
    val openNetworkUrl: String = "",
    val fibreVisionUrl: String = "",
)

fun main(args: Array<String>) {
    runApplication<ClearwaveApplication>(*args)
}
