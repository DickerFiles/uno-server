FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY uno-shared /app/uno-shared
WORKDIR /app/uno-shared
RUN mvn clean install -DskipTests
WORKDIR /app
COPY uno-server /app/uno-server
WORKDIR /app/uno-server
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/uno-server/target/uno-server-1.0-SNAPSHOT.jar /app/server.jar
EXPOSE 5050
CMD ["java", "-jar", "server.jar"]