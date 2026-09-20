FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

COPY backend/gradle gradle
COPY backend/gradlew backend/build.gradle backend/settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY backend/src src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"
EXPOSE 8080
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
