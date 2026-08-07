# Shared image build for every FinPay Spring Boot service.
#
# Parameterised by module name rather than copied per service, so a change to the build or
# runtime setup applies everywhere instead of drifting across eleven near-identical files.
#
#   docker build -f infrastructure/docker/service.Dockerfile \
#     --build-arg MODULE=config-server --build-arg SERVICE_PORT=8888 .
#
# The build context is the repository root: the Maven reactor needs the parent POM and the
# sibling module POMs to resolve the module being built.

# --- Build ---------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

ARG MODULE
WORKDIR /build

# Build descriptors first: dependency resolution is the slow part and only needs to re-run
# when a POM changes, not when source changes.
# Every module listed in the parent POM must be present or the reactor refuses to load, even
# when only one of them is being built.
COPY pom.xml ./
COPY finpay-platform-web/pom.xml finpay-platform-web/
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
    mvn -B -pl ${MODULE} -am dependency:go-offline -DskipTests

# Every service depends on the shared cross-cutting module, and -am builds it from source.
COPY finpay-platform-web/src finpay-platform-web/src
COPY ${MODULE}/src ${MODULE}/src

# Tests and formatting are gates in `make verify` and in CI. Running them here would
# duplicate that work on every container rebuild without adding a check.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl ${MODULE} -am package -DskipTests -Dspotless.check.skip=true

# The jar name embeds the module, which is not known to the runtime stage's COPY. Normalise
# it here so the runtime stage can copy a fixed path.
RUN cp ${MODULE}/target/${MODULE}-*.jar /build/application.jar

# --- Runtime -------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

ARG SERVICE_PORT

RUN addgroup -S finpay && adduser -S -G finpay finpay

WORKDIR /app
COPY --from=build --chown=finpay:finpay /build/application.jar app.jar

USER finpay
EXPOSE ${SERVICE_PORT}

# MaxRAMPercentage sizes the heap from the container limit; without it the JVM reads the
# host's memory and can be killed by the cgroup. ExitOnOutOfMemoryError makes the container
# die and restart rather than linger in a broken state.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
