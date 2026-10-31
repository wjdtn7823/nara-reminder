import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    id("org.springframework.boot") version "3.5.6" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.spring") version "2.2.20" apply false
    kotlin("plugin.jpa") version "2.2.20" apply false
}

subprojects {
    group = "com.narareminder"
    version = "0.0.1"

    repositories {
        mavenCentral()
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                freeCompilerArgs.addAll("-Xjsr305=strict")
            }
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
