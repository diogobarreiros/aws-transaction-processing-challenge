FROM gradle:8.6-jdk17 AS build

WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Copy source code
COPY src src

# Make gradlew executable
RUN chmod +x ./gradlew

# Build the Spring Boot application
# Use --no-daemon for CI/CD environments
# Use clean build for fresh build
RUN ./gradlew clean build --no-daemon

# Stage 2: Create the final image
FROM eclipse-temurin:17-jre-focal

# Set the working directory
WORKDIR /app

# Copy the built JAR from the build stage
COPY --from=build /app/build/libs/app-consumer-0.0.1-SNAPSHOT.jar app-consumer.jar

# Expose port 8080 for Actuator health checks (if needed)
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app-consumer.jar"]