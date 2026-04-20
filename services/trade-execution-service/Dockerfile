# Stage 1: Install shared-trade-core into local Maven repo
FROM maven:3.9-eclipse-temurin-21-alpine AS shared

WORKDIR /build/shared-trade-core
COPY shared-trade-core/pom.xml ./pom.xml
COPY shared-trade-core/src ./src
RUN mvn install -DskipTests -B

# Stage 2: Build trade-execution-service (reads shared module from the reused /root/.m2)
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

COPY --from=shared /root/.m2 /root/.m2

WORKDIR /build
COPY services/trade-execution-service/pom.xml ./pom.xml
RUN mvn dependency:go-offline -B

COPY services/trade-execution-service/src/ ./src/
RUN mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar

# Stage 3: Runtime
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -g 1001 appgroup && \
    adduser -u 1001 -G appgroup -D appuser

WORKDIR /app

COPY --from=builder /build/target/*-runner.jar app.jar

EXPOSE 8087

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8087/q/health/ready || exit 1

USER 1001

CMD ["java", "-jar", "app.jar"]
