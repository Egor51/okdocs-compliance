FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# Сначала только pom'ы: слой с зависимостями кэшируется и не пересобирается
# при изменении исходников (ускоряет CI и локальные пересборки).
COPY pom.xml ./
COPY compliance-contracts/pom.xml compliance-contracts/
COPY compliance-persistence/pom.xml compliance-persistence/
COPY compliance-messaging/pom.xml compliance-messaging/
COPY compliance-rules/pom.xml compliance-rules/
COPY compliance-api/pom.xml compliance-api/
COPY compliance-worker/pom.xml compliance-worker/
COPY compliance-app/pom.xml compliance-app/
RUN mvn -B -ntp -pl compliance-api -am -DskipTests dependency:go-offline || true

COPY . .
RUN mvn -B -ntp -pl compliance-api -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd --system compliance \
    && useradd --system --gid compliance --home-dir /app --shell /usr/sbin/nologin compliance

COPY --from=build /workspace/compliance-api/target/compliance-api-*-exec.jar /app/app.jar

USER compliance

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
