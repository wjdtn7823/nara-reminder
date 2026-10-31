plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation(testFixtures(project(":core")))
    testImplementation("io.mockk:mockk:1.14.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// bootJar 하나만 남겨 Dockerfile 이 jar 를 하나로 집을 수 있게 한다
tasks.named<Jar>("jar") {
    enabled = false
}
