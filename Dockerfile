FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app

# Instalar Maven
RUN apk add --no-cache maven

# Copiar archivos de proyecto
COPY pom.xml .
COPY src ./src

# Compilar con Maven
RUN mvn clean package -DskipTests

EXPOSE 5050

CMD ["java", "-jar", "target/uno-servidor-1.0-SNAPSHOT.jar"]