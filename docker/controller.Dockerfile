# Image du controller Ksmc.
# Prérequis : ./gradlew :ksmc-controller:installDist (la CI le fait avant le build d'image).
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 ksmc
COPY ksmc-controller/build/install/ksmc-controller /opt/ksmc
USER 10001
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["/opt/ksmc/bin/ksmc-controller"]
