# ---- Stage 1: build the JAR ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom first so dependency downloads are cached between builds
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Tests run in the CI pipeline's own test stage, so skip them here
RUN mvn -B package -DskipTests

# ---- Stage 2: small runtime image ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as a non-root user
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /app/target/task-manager.jar app.jar

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
