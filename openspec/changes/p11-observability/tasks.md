# Tasks

## 1. Métriques

- [x] 1.1 Micrometer dans le controller, métriques services/joueurs/réconciliation/API/modules ; tests unitaires
- [ ] 1.2 `/metrics` du wrapper (JVM, joueurs, TPS via bridge, uptime) ; tests unitaires
- [ ] 1.3 Templates chart ServiceMonitor/PodMonitor/PrometheusRule + dashboard Grafana ; vérifier `helm template` avec les options

## 2. Logs et validation

- [ ] 2.1 Logs JSON optionnels, niveau à chaud via API ; tests
- [ ] 2.2 Scénario e2e : scrape `/metrics` du controller et d'un service ; `docs/OBSERVABILITY.md` ; vérifier en CI
