FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
RUN apk add --no-cache maven
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests
EXPOSE 5050
CMD ["java", "-jar", "target/uno-servidor-1.0-SNAPSHOT.jar"]
