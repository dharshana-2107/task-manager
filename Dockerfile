# Packages the JAR that Maven already built and tested in the pipeline,
# so the image contains exactly the tested artifact.
# Locally: run `mvn package` first, then `docker build`.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as a non-root user
RUN addgroup -S app && adduser -S app -G app

COPY target/task-manager.jar app.jar

USER app

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
