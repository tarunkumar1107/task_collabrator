# Build stage — pinned Java 21 so local JDK version does not matter
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -q -DskipTests package

# Runtime stage — slim JRE 21
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN mkdir -p /app/uploads \
    && groupadd --system taskmaster \
    && useradd --system --gid taskmaster --home-dir /app --shell /usr/sbin/nologin taskmaster \
    && chown -R taskmaster:taskmaster /app

COPY --from=build /app/target/taskmaster-*.jar /app/app.jar

USER taskmaster

ENV SERVER_PORT=8080 \
    APP_UPLOAD_DIR=/app/uploads \
    JAVA_OPTS=""

EXPOSE 8080

VOLUME ["/app/uploads"]

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
