# Design

## Decisions

1. Micrometer + `micrometer-registry-prometheus` dans le controller ; le registre est exposé aux modules via `ModuleContext.metrics()` avec préfixe imposé.
2. Wrapper (Java 8) : exposition Prometheus texte écrite à la main (pas de dépendance), données JVM via JMX, TPS reçu du bridge par socket local.
3. Logback avec encodeur JSON (`logstash-logback-encoder`) activable ; niveau via `PUT /api/v1/admin/loglevel`.
4. Dashboard Grafana JSON versionné dans `deploy/grafana/`.
