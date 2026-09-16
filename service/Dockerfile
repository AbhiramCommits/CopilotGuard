FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre AS runtime
RUN groupadd --system copilotguard \
    && useradd --system --gid copilotguard --create-home copilotguard
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER copilotguard
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
