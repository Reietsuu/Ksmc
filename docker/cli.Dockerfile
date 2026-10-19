# Image de la CLI : docker run --rm -it -v ~/.ksmc:/home/ksmc/.ksmc ghcr.io/reietsuu/ksmc-cli task list
FROM eclipse-temurin:21-jre
RUN useradd --create-home --uid 10001 ksmc
COPY ksmc-cli/build/install/ksmc /opt/ksmc-cli
USER 10001
WORKDIR /home/ksmc
ENTRYPOINT ["/opt/ksmc-cli/bin/ksmc"]
