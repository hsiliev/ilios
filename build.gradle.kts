plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("io.ktor.plugin") version "3.6.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("dev.detekt") version "2.0.0-alpha.6"
}

group = "ilios"
version = "1.0"

repositories { mavenCentral() }

val ktor = "3.6.0"
val exposed = "1.5.1"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("io.insert-koin:koin-ktor:4.2.2")
    implementation("org.jetbrains.exposed:exposed-core:$exposed")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed")
    implementation("org.jetbrains.exposed:exposed-migration-jdbc:$exposed")
    implementation("com.h2database:h2:2.5.252")
    implementation("org.eclipse.angus:angus-mail:2.0.5")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("io.ktor:ktor-client-mock:$ktor")
    testImplementation(kotlin("test"))
}

application { mainClass.set("ilios.MainKt") }

tasks.test { useJUnitPlatform() }

detekt {
    buildUponDefaultConfig = true
    allRules = true
    config.setFrom("config/detekt.yml")
}
