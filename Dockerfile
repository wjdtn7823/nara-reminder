FROM eclipse-temurin:21-jdk AS build
ARG MODULE
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY core/build.gradle.kts core/
COPY api/build.gradle.kts api/
COPY worker/build.gradle.kts worker/
RUN ./gradlew --no-daemon :${MODULE}:dependencies > /dev/null
COPY core/src core/src
COPY ${MODULE}/src ${MODULE}/src
RUN ./gradlew --no-daemon :${MODULE}:bootJar

FROM eclipse-temurin:21-jre
ARG MODULE
ENV TZ=Asia/Seoul
WORKDIR /app
COPY --from=build /workspace/${MODULE}/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]
