plugins {
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

val resilience4jVersion = "2.2.0"
val testcontainersVersion = "1.20.1"
val macstabChaosVersion = "1.0.0-SNAPSHOT"

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.4")
        mavenBom("org.testcontainers:testcontainers-bom:$testcontainersVersion")
    }
}

dependencies {
    // ── Main ──────────────────────────────────────────────────────────────
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:$resilience4jVersion")
    implementation("redis.clients:jedis")

    // ── Test ──────────────────────────────────────────────────────────────
    // Macstab chaos core
    testImplementation("com.macstab.chaos:macstab-chaos-java-spring-boot3-test:$macstabChaosVersion")
    testImplementation("com.macstab.chaos:macstab-chaos-redis:$macstabChaosVersion")
    testImplementation("com.macstab.chaos:macstab-chaos-patterns:$macstabChaosVersion")

    // Macstab testpacks
    testImplementation("com.macstab.chaos:macstab-chaos-testpacks-connection:$macstabChaosVersion")
    testImplementation("com.macstab.chaos:macstab-chaos-testpacks-dns:$macstabChaosVersion")
    testImplementation("com.macstab.chaos:macstab-chaos-testpacks-l3-redis:$macstabChaosVersion")
    testImplementation("com.macstab.chaos:macstab-chaos-testpacks-l3-kubernetes:$macstabChaosVersion")
    testImplementation("com.macstab.chaos:macstab-chaos-testpacks-l3-jvm:$macstabChaosVersion")
    testImplementation("com.macstab.chaos:macstab-chaos-testpacks-l3-spring:$macstabChaosVersion")
    testImplementation("com.macstab.chaos:macstab-chaos-testpacks-l3-feign:$macstabChaosVersion")

    // Spring Boot test slice
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Resilience4j
    testImplementation("io.github.resilience4j:resilience4j-spring-boot3:$resilience4jVersion")
    testImplementation("io.github.resilience4j:resilience4j-circuitbreaker:$resilience4jVersion")
    testImplementation("io.github.resilience4j:resilience4j-retry:$resilience4jVersion")

    // Testcontainers
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")

    // WireMock
    testImplementation("org.wiremock:wiremock-standalone:3.3.1")

    // Redis client
    testImplementation("redis.clients:jedis")

    // Assertions & concurrency
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.awaitility:awaitility:4.2.1")

    // Logging
    testImplementation("ch.qos.logback:logback-classic")
}
