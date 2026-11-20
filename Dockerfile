# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sh ./mvnw -B -q dependency:go-offline
COPY src src
RUN sh ./mvnw -B -q -DskipTests package && cp target/*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd -r -u 1001 ceta
USER ceta
COPY --from=build /app.jar /app.jar
ENV SPRING_PROFILES_ACTIVE=prod JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app.jar"]
