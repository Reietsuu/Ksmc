# Tests manuels

Procédure pas à pas pour vérifier soi-même chaque capacité de Ksmc sur un cluster de test. Chaque étape donne les
commandes à exécuter et le **résultat attendu**. Durée : environ 30 minutes (dont le téléchargement des serveurs).

Les blocs marqués « vérifié en CI » sont exécutés tels quels à la fin du job e2e (`scripts/testing-md.sh`) : ils
échouent si le résultat attendu n'est pas obtenu.

## 0. Préparation

Un cluster kind dédié (4 CPU et 8 Go de RAM libres conseillés) et les outils :

```bash
scripts/tools.sh                                   # kind, kubectl, helm dans .tools/bin
export PATH="$PWD/.tools/bin:$PATH"
export KUBECONFIG="$PWD/.tools/kubeconfig-ksmc-test"
kind create cluster --name ksmc-test
./gradlew :ksmc-cli:installDist && export PATH="$PWD/ksmc-cli/build/install/ksmc/bin:$PATH"
```

Installer Ksmc depuis le dépôt (images publiées de la version du chart) :

```bash
helm dependency update deploy/helm/ksmc
helm install ksmc deploy/helm/ksmc -n ksmc --create-namespace --set minecraft.acceptEula=true --wait
```

**Attendu** : `helm` affiche `STATUS: deployed` et les notes d'installation.

Variables et fonction d'attente utilisées ensuite, redirection de port vers l'API et le dashboard (port local 18080) :

```bash test
export NS=ksmc API=http://localhost:18080
# attendre <secondes> <commande...> : réessaie chaque seconde jusqu'au succès
attendre() { local t=$1; shift; for _ in $(seq "$t"); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done; echo "ÉCHEC : $*" >&2; return 1; }
phase() { kubectl -n $NS get mcsvc "$1" -o jsonpath='{.status.phase}' 2>/dev/null; }
en_ligne() { [ "$(phase "$1")" = Running ]; }
# contient <motif> : comme grep -q, mais lit toute l'entrée (grep -q coupe le tube et fait échouer curl)
contient() { grep "$@" >/dev/null; }
# redirection en arrière-plan, relançable (arrête l'ancienne ; le sous-shell évite le message « [1] PID »)
pkill -f 'port-forward svc/ksmc-controller 18080' || true
(kubectl -n $NS port-forward svc/ksmc-controller 18080:8080 --address 0.0.0.0 >/dev/null 2>&1 &)
attendre 30 curl -sf $API/healthz
```

**Attendu** : aucune sortie, la commande rend la main.

## 1. Installation

```bash test
kubectl -n $NS rollout status deploy/ksmc-controller --timeout=180s
kubectl -n $NS get pods
test "$(kubectl api-resources --api-group=ksmc.io -o name | wc -l)" -eq 6
kubectl -n $NS get mcsw paper velocity spigot fabric neoforge
curl -s $API/readyz
```

**Attendu** : `ksmc-controller-…` et `ksmc-nats-0` en `Running` ; les 5 logiciels listés ; `/readyz` répond
`{"ready":true,…}` avec `kubernetes` et `nats` à `up`.

## 2. Connexion (CLI)

Le mot de passe initial de `admin` est dans le Secret `ksmc-admin` ; il faut en choisir un nouveau à la première
connexion (`KSMC_PASSWORD`, au moins 8 caractères).

```bash test
export KSMC_PASSWORD=${KSMC_PASSWORD:-motdepasse-de-test}
INITIAL=$(kubectl -n $NS get secret ksmc-admin -o jsonpath='{.data.password}' | base64 -d)
ksmc login $API -u admin -p "$INITIAL" --new-password "$KSMC_PASSWORD" --name test \
  || ksmc login $API -u admin -p "$KSMC_PASSWORD" --name test
ksmc user me
```

**Attendu** : `connecté à … (contexte test)` puis `admin` avec la permission `*`.

## 3. Proxy et lobby

```bash test
ksmc task create t-proxy -s velocity:latest -e proxy --min 1 -m 512
ksmc task create t-lobby -s paper:latest --min 1 -m 1024
attendre 900 en_ligne t-proxy-1
attendre 900 en_ligne t-lobby-1
ksmc service list -t t-lobby
kubectl -n $NS get mctask t-lobby
```

**Attendu** : les deux tasks sont créées, puis `t-proxy-1` et `t-lobby-1` passent `Pending → Preparing → Starting →
Running` (premier démarrage : téléchargement du logiciel, 1 à 5 minutes). `kubectl get mctask` affiche `READY 1/1`.

Le proxy connaît le lobby (bridge) :

```bash test
attendre 60 sh -c "ksmc service logs t-proxy-1 -n 200 | grep -q 'serveur ajouté : t-lobby-1'"
```

**Attendu** : la ligne `serveur ajouté : t-lobby-1` est dans les logs du proxy.

## 4. Console et commandes

```bash test
ksmc service cmd t-lobby-1 list
attendre 30 sh -c "ksmc service logs t-lobby-1 -n 50 | grep -q 'players online'"
```

**Attendu** : `ok`, puis la réponse du serveur (`There are 0 of a max of 20 players online`) dans ses logs.

Commande réseau sur le proxy :

```bash test
ksmc service cmd t-proxy-1 glist
attendre 30 sh -c "ksmc service logs t-proxy-1 -n 50 | grep -q 'joueur(s) en ligne sur le réseau'"
```

**Attendu** : `0 joueur(s) en ligne sur le réseau` dans les logs du proxy (tous proxies confondus).
En interactif : `ksmc service console t-lobby-1` (Ctrl+D pour quitter).

## 5. Montée en charge, maintenance

```bash test
ksmc task start t-lobby -n 1
attendre 600 en_ligne t-lobby-2
ksmc task maintenance t-lobby on
kubectl -n $NS get mctask t-lobby -o jsonpath='{.spec.maintenance}' | contient -x true
ksmc task maintenance t-lobby off
ksmc service stop t-lobby-2
attendre 120 sh -c "! kubectl -n $NS get mcsvc t-lobby-2"
```

**Attendu** : `t-lobby-2` est créé puis `Running` ; la maintenance passe à `true` puis `false` ; le service
arrêté disparaît (le minimum de 1 est respecté par `t-lobby-1`).

## 6. Templates

```bash test
printf 'motd=Ksmc test\n' > /tmp/ksmc-server.properties
ksmc template put t/lobby server.properties /tmp/ksmc-server.properties
ksmc template ls t/lobby | contient server.properties
ksmc template cat t/lobby server.properties | contient -x 'motd=Ksmc test'
```

**Attendu** : le fichier est listé et relu à l'identique. Un template se rattache à une task par une ressource
`ServiceTemplate` (`spec.storage.path: t/lobby`) et `spec.templates` de la task (voir [USAGE.md](USAGE.md)).

## 7. Service statique

```bash test
ksmc task create t-static -s velocity:latest -e proxy --min 1 -m 512 --static
attendre 900 en_ligne t-static-1
kubectl -n $NS exec "$(kubectl -n $NS get mcsvc t-static-1 -o jsonpath='{.status.podName}')" -c server -- \
  sh -c 'echo conserve > /server/marqueur.txt'
ksmc service restart t-static-1
sleep 10 && attendre 600 en_ligne t-static-1
kubectl -n $NS exec "$(kubectl -n $NS get mcsvc t-static-1 -o jsonpath='{.status.podName}')" -c server -- \
  cat /server/marqueur.txt | contient -x conserve
```

**Attendu** : après le redémarrage, le service garde son nom et le fichier `marqueur.txt` (volume persistant).

## 8. API REST

```bash test
TOKEN=$(ksmc token create test-manuel --days 1)
curl -sf -H "Authorization: Bearer $TOKEN" $API/api/v1/tasks | contient '"t-lobby"'
curl -s -o /dev/null -w '%{http_code}\n' $API/api/v1/tasks | contient -x 401
curl -sf $API/api/v1/openapi.json | contient '"openapi"'
```

**Attendu** : la liste des tasks (JSON) avec un jeton d'API, `401` sans jeton ; la description OpenAPI est publique
(Swagger UI : `http://localhost:18080/api/docs`).

## 9. Observabilité

```bash test
attendre 60 sh -c "curl -s $API/metrics | grep '^ksmc_services{phase=\"Running\",task=\"t-lobby\"} 1.0' >/dev/null" \
  || { curl -s $API/metrics | grep '^ksmc_services{.*t-lobby'; false; }
POD=$(kubectl -n $NS get mcsvc t-lobby-1 -o jsonpath='{.status.podName}')
pkill -f 'port-forward pod/.* 18090:8090' || true
(kubectl -n $NS port-forward "pod/$POD" 18090:8090 >/dev/null 2>&1 &)
attendre 60 sh -c "curl -s localhost:18090/metrics | grep -q '^ksmc_service_tps'"
curl -s -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"logger":"io.ksmc","level":"DEBUG"}' $API/api/v1/admin/loglevel | contient '"effective":"DEBUG"'
```

**Attendu** : `ksmc_services` vaut 1 pour `t-lobby` en `Running` ; le service expose `ksmc_service_tps` (bridge
Paper) ; le niveau de log passe à `DEBUG` sans redémarrage. Voir [OBSERVABILITY.md](OBSERVABILITY.md).

## 10. Dashboard (manuel)

Ouvrir `http://localhost:18080` (ou `http://<ip-de-la-machine>:18080` depuis un autre poste : la redirection écoute sur `0.0.0.0`), se connecter avec `admin` / `$KSMC_PASSWORD`.

**Attendu** :
- la vue d'ensemble affiche 3 services et les tasks `t-proxy`, `t-lobby`, `t-static` ;
- `Services → t-lobby-1` : la console montre les logs ; taper `list` affiche la réponse du serveur ;
- `Templates` : créer un template `storage` (`t/lobby`), ouvrir `server.properties`, le modifier et enregistrer ;
- `Tasks → Nouvelle task` crée une task visible avec `kubectl -n ksmc get mctask` ;
- en réduisant la fenêtre à la largeur d'un téléphone, le menu devient un bouton ☰ et les tableaux des cartes.

## 11. Joueur réel (manuel)

```bash
kubectl -n ksmc patch mctask t-proxy --type merge -p '{"spec":{"properties":{"ksmc.online-mode":"false"},"expose":{"type":"ClusterIP"}}}'
kubectl -n ksmc port-forward svc/t-proxy 25565
```

Se connecter avec un client Minecraft (version du lobby) à `localhost:25565`.
**Attendu** : arrivée sur `t-lobby-1` ; `ksmc player list` vous affiche ; `ksmc player send <pseudo> -s t-lobby-2`
(après `ksmc task start t-lobby`) vous déplace ; `ksmc player kick <pseudo>` vous déconnecte.

## 12. Modules (manuel)

```bash
./gradlew -p examples/module-hello build
kubectl -n ksmc cp examples/module-hello/build/libs/ksmc-module-hello.jar \
  "$(kubectl -n ksmc get pod -l app.kubernetes.io/component=controller -o name | cut -d/ -f2)":/data/modules/hello.jar
ksmc module install hello --store modules/hello.jar -c greeting=Bonjour
ksmc hello broadcast "Bonjour à tous"
```

**Attendu** : `ksmc module list` montre `hello` en `Enabled` ; une entrée « Hello » apparaît dans le menu du
dashboard ; `curl -s localhost:18080/metrics | grep ksmc_module_hello` après un appel à la page.

## 13. Nettoyage

```bash test
ksmc task delete t-proxy t-lobby t-static
attendre 300 sh -c "! kubectl -n $NS get mcsvc -o name | grep -q '/t-'"
kubectl -n $NS delete pvc -l ksmc.io/service=t-static-1 --ignore-not-found
ksmc template rm t/lobby server.properties
ksmc token list | awk '$2 == "test-manuel" {print $1}' | xargs -r -n1 ksmc token revoke
pkill -f "kubectl -n $NS port-forward" || true
```

**Attendu** : tous les services `t-…` disparaissent. Pour tout supprimer : `kind delete cluster --name ksmc-test`.
