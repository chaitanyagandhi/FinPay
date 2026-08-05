# Build stage. Runs from the repository root so the Maven reactor can resolve the parent POM.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Copy the build descriptors first: dependency resolution is the slow part and only needs to
# re-run when a POM changes, not when source changes.
COPY pom.xml ./
COPY api-gateway/pom.xml api-gateway/
COPY audit-service/pom.xml audit-service/
COPY auth-service/pom.xml auth-service/
COPY config-server/pom.xml config-server/
COPY fraud-service/pom.xml fraud-service/
COPY notification-service/pom.xml notification-service/
COPY payment-service/pom.xml payment-service/
COPY service-registry/pom.xml service-registry/
COPY transaction-service/pom.xml transaction-service/
COPY user-service/pom.xml user-service/
COPY wallet-service/pom.xml wallet-service/

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl config-server -am dependency:go-offline -DskipTests

COPY config-server/src config-server/src

# Tests and formatting are gates in `make verify` and in CI, not in the image build; running
# them here would duplicate the work and slow every container rebuild.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl config-server -am package -DskipTests -Dspotless.check.skip=true

# Runtime stage. JRE only: the compiler and Maven have no business in a running container.
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S finpay && adduser -S -G finpay finpay

WORKDIR /app
COPY --from=build --chown=finpay:finpay /build/config-server/target/config-server-*.jar app.jar

USER finpay
EXPOSE 8888

# MaxRAMPercentage lets the JVM size its heap from the container limit rather than from the
# host's memory, which it would otherwise misread.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
