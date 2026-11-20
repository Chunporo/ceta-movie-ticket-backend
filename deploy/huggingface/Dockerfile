# Hugging Face Space (Docker SDK). The Space repo holds only this file and README.md; the code is cloned from GitHub at build time.
# Rebuild after a push: Space > Settings > Factory rebuild (the ADD line below breaks the cache when the branch moves).
FROM eclipse-temurin:21-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
ARG BRANCH=dev
ADD https://api.github.com/repos/Chunporo/ceta-movie-ticket-backend/git/refs/heads/${BRANCH} /tmp/ref.json
RUN git clone --depth 1 -b ${BRANCH} https://github.com/Chunporo/ceta-movie-ticket-backend.git /src
WORKDIR /src
RUN sh ./mvnw -B -q -DskipTests package && cp target/*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd -m -u 1000 user
USER user
COPY --from=build /app.jar /home/user/app.jar
# Spaces sends traffic to 7860. All other settings (DATABASE_*, JWT_SECRET, ...) are Space secrets / variables.
ENV SPRING_PROFILES_ACTIVE=prod PORT=7860 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC"
EXPOSE 7860
ENTRYPOINT ["java","-jar","/home/user/app.jar"]
