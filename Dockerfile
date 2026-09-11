FROM eclipse-temurin:11-jdk AS build
WORKDIR /app

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew

COPY src ./src
RUN ./gradlew --no-daemon buildFatJar

FROM eclipse-temurin:11-jre AS runtime
WORKDIR /app

COPY --from=build /app/build/libs/SongsServer.jar ./SongsServer.jar

EXPOSE 2403
ENTRYPOINT ["java", "-jar", "SongsServer.jar"]
