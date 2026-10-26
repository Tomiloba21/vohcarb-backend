# Builds the backend inside Docker, so the server needs no JDK.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Dependencies first: this layer is reused until the build files change.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar \
 && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} app.jar \;

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 vohcarb
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER vohcarb
EXPOSE 8080
# Use most of the container's memory limit for the heap.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
