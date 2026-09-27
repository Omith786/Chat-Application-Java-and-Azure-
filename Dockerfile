# Multi-stage build: compile with the Maven Wrapper on a JDK, run on a slim JRE as a non-root user.
#   docker build -t chat-server .
#   docker run -p 8080:8080 -e CHAT_SESSION_SECRET=... chat-server

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY chat-protocol/ chat-protocol/
COPY chat-cli/ chat-cli/
COPY chat-server/ chat-server/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /app chat \
    && mkdir -p /app/data && chown -R chat /app
WORKDIR /app
COPY --from=build /src/chat-server/target/chat-server.jar /app/chat-server.jar
USER chat
ENV CHAT_DATA_DIR=/app/data \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
VOLUME /app/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/chat-server.jar"]
