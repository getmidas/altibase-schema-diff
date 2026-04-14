FROM gradle:9.3-jdk25 AS builder
WORKDIR /build
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle shadowJar --no-daemon -q

FROM eclipse-temurin:25-jre-jammy AS runtime
WORKDIR /app
COPY --from=builder /build/build/libs/altibase-schema-diff.jar /app/
ENTRYPOINT ["java", "-jar", "/app/altibase-schema-diff.jar"]
