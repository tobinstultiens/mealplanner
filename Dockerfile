# Runtime image only: the fat jar is built by Gradle beforehand
# (./gradlew :server:shadowJar), locally or in CI.
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 --home /data mealplanner && mkdir /data && chown mealplanner /data
COPY server/build/libs/server-all.jar /app/server.jar
USER mealplanner
WORKDIR /data
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/server.jar"]
