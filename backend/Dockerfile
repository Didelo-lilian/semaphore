FROM gradle:9.8.2-jdk25-alpine AS build
WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:26.0.2_10-jre-alpine
LABEL org.opencontainers.image.source=https://github.com/Didelo-lilian/semaphore
# Not root, and nothing writes outside /tmp: the container can run with a read-only file system.
RUN adduser -D -u 10001 semaphore
USER semaphore
COPY --from=build /app/build/libs/semaphore.jar /app/semaphore.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/semaphore.jar"]
