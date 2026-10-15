# Image d'exécution des services Minecraft : JRE + ksmc-wrapper.
# Variantes : --build-arg JAVA_VERSION=8|17|21|25 → ghcr.io/reietsuu/ksmc-runtime:<version>-java<N>
# Prérequis : ./gradlew :ksmc-wrapper:jar
ARG JAVA_VERSION=21
FROM eclipse-temurin:${JAVA_VERSION}-jre
RUN groupadd --system --gid 1000 minecraft 2>/dev/null || true \
 && useradd --system --uid 1000 --gid 1000 --home-dir /server minecraft 2>/dev/null || true \
 && mkdir -p /server /opt/ksmc && chown 1000:1000 /server
COPY ksmc-wrapper/build/libs/ksmc-wrapper.jar /opt/ksmc/ksmc-wrapper.jar
USER 1000
WORKDIR /server
EXPOSE 25565 8090
ENTRYPOINT ["java", "-jar", "/opt/ksmc/ksmc-wrapper.jar"]
CMD ["run"]
