import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    java
    id("com.diffplug.spotless") version "6.25.0" apply false
}

allprojects {
    group = "com.macstab.chaos.examples"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    configure<SpotlessExtension> {
        java {
            googleJavaFormat("1.19.1")
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint("1.2.1")
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("--enable-preview", "-Xlint:preview"))
    }

    tasks.withType<Test> {
        jvmArgs("--enable-preview")
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
