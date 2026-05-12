plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kensa.gradle)
}

kensa {
    sourceSets = setOf("test")
    sourceTitles.put("test", "Clearwave Spring Acceptance Tests")
}

configurations.all {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.get()}")
}

group = "com.clearwave"
version = "1.0.0"

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        name = "centralSnapshots"
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
    }
}

configurations.all {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.get()}")
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.jsr310)
    implementation(libs.jackson.xml)

    testImplementation(libs.kensa.spring.boot.starter)
    testImplementation(libs.kensa.spring.boot.starter.web)
    testImplementation(libs.kensa.assertions.kotest)
    testImplementation(libs.kensa.kotest.test.support)
    testImplementation(libs.kensa.kotest.test.support.xml)
    testImplementation(libs.kensa.kotest.test.support.json)
    testImplementation(libs.kensa.assertions.hamcrest)
    testImplementation(libs.hamcrest)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(platform(libs.kotest.bom))
    testImplementation(libs.kotest.assertions)
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
}

kotlin {
    jvmToolchain(21)
}


tasks.test {
    useJUnitPlatform()
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Djava.awt.headless=true") })
}
