# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Cache dependencies separately from source code
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Czcionki z obsługą Unicode (polskie znaki w generowanych PDF-ach)
RUN apk add --no-cache font-dejavu

RUN addgroup -S ksef && adduser -S ksef -G ksef

COPY --from=build /app/target/*.jar app.jar

USER ksef
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
