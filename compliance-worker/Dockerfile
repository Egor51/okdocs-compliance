FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY . .
RUN mvn -pl compliance-worker -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd --system compliance \
    && useradd --system --gid compliance --home-dir /app --shell /usr/sbin/nologin compliance

COPY --from=build /workspace/compliance-worker/target/compliance-worker-*-exec.jar /app/app.jar

USER compliance

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
