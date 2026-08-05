plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

group = "com.sec"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
    explicitApi()
}

application {
    mainClass.set("com.sec.ApplicationKt")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.config.yaml)

    implementation(libs.neo4j.driver)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.neo4j)
    testImplementation(libs.testcontainers.junit.jupiter)
}

// Tests that need a container are tagged "docker" and are not part of `check`. Not every machine
// that builds this has Docker — the DOORS importer's own target is a Windows workstation — and a
// `check` that cannot pass locally is a `check` that gets skipped, taking the other tests with it.
tasks.test {
    useJUnitPlatform { excludeTags("docker") }
    // The Neo4j image the container tests pull, pinned next to every other version.
    systemProperty("sec.test.neo4jImage", libs.versions.neo4j.image.get())
}

tasks.register<Test>("integrationTest") {
    description = "Runs the tests that require Docker (Testcontainers against Neo4j Community)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("docker") }
    systemProperty("sec.test.neo4jImage", libs.versions.neo4j.image.get())
    shouldRunAfter(tasks.test)
}
