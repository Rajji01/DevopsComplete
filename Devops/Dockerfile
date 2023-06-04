# Use a base image with Java 17 installed
#FROM adoptopenjdk:17-jdk-hotspot
FROM openjdk:17-jdk-alpine

# Set the working directory inside the container
WORKDIR /app

# Copy the JAR file into the container
COPY target/Devops-0.0.1-SNAPSHOT.jar app.jar

# Expose the port your Spring Boot application is listening on (if applicable)
EXPOSE 8080
RUN echo "running the images"
# Set any necessary environment variables (if applicable)
ENV TESTING_PROPERTY "myval"

# Start the Spring Boot application
CMD ["java", "-jar", "app.jar"]
