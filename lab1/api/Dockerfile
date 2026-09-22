FROM maven:3.9-eclipse-temurin-21
WORKDIR /app
COPY . .
RUN mvn -B package -DskipTests
CMD ["java", "-jar", "target/api-1.0.0.jar"]