import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    `java-library`
    `java-test-fixtures`
    id("io.spring.dependency-management")
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

// Spring Boot BOM 이 관리하는 Kotlin 버전이 플러그인 버전보다 낮아 컴파일러 클래스패스가 깨지므로 맞춘다
extra["kotlin.version"] = "2.2.20"

dependencyManagement {
    imports {
        mavenBom(SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework:spring-web")
    api("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    api("com.fasterxml.jackson.module:jackson-module-kotlin")
    api("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")

    testFixturesApi("org.springframework.boot:spring-boot-starter-test") {
        exclude(module = "mockito-core")
    }
    testFixturesApi("org.springframework.boot:spring-boot-testcontainers")
    testFixturesApi("org.testcontainers:junit-jupiter")
    testFixturesApi("org.testcontainers:postgresql")
    testImplementation("io.mockk:mockk:1.14.5")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
