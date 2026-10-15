# service-lifecycle Specification

## Purpose
Faire correspondre chaque `CloudService` à des ressources Kubernetes et refléter fidèlement l'état
réel de l'instance dans son `status`.

## Requirements

### Requirement: Ressources Kubernetes d'un service
Pour chaque `CloudService`, le controller SHALL créer un Pod (labels `ksmc.io/service`, `ksmc.io/task`,
`ksmc.io/environment`) possédé par le service, et pour un service statique un PersistentVolumeClaim conservé entre les redémarrages.

#### Scenario: Service dynamique
- **WHEN** un `CloudService` dynamique est supprimé
- **THEN** son Pod est supprimé et aucune donnée n'est conservée

#### Scenario: Service statique
- **WHEN** un `CloudService` statique `survival-1` est recréé après suppression de son Pod
- **THEN** le nouveau Pod monte le même PVC et retrouve le monde précédent

### Requirement: Phases du service
Le `status.phase` SHALL suivre : `Pending` (Pod non planifié), `Preparing` (préparation), `Starting` (process
lancé, pas prêt), `Running` (ping OK), `Stopping` (arrêt demandé), `Stopped` (process terminé), `Failed`
(erreur, avec `reason` et `message`).

#### Scenario: Démarrage nominal
- **WHEN** un `CloudService` Paper valide est créé
- **THEN** sa phase passe par `Preparing` et `Starting` puis atteint `Running` et `status.address` contient l'IP:port du Pod

### Requirement: Personnalisation du Pod
Le Pod SHALL appliquer, dans l'ordre, la configuration par défaut, le `podTemplate` des groupes puis celui de la
task (fusion stratégique Kubernetes), permettant ressources, nodeSelector, tolérances, affinités, volumes,
sidecars, classe de priorité et toute autre fonctionnalité de Pod.

#### Scenario: Sidecar et nodeSelector
- **WHEN** une task ajoute un conteneur sidecar et `nodeSelector: {pool: games}`
- **THEN** le Pod du service contient le sidecar et est planifié sur un nœud `pool=games`

### Requirement: Arrêt et redémarrage
Supprimer un `CloudService` SHALL déclencher l'arrêt gracieux ; mettre `spec.desiredState: Stopped` SHALL
arrêter le Pod sans supprimer le service ; un service statique arrêté SHALL garder son PVC.

#### Scenario: Arrêt demandé
- **WHEN** l'utilisateur met `desiredState: Stopped` sur un service en `Running`
- **THEN** le service passe en `Stopping` puis `Stopped`, et aucun Pod ne tourne

### Requirement: Diagnostic d'échec
Quand un service échoue, le controller SHALL renseigner `reason`, `message` et un événement Kubernetes,
y compris pour les erreurs de planification (ressources insuffisantes) et d'image.

#### Scenario: Mémoire insuffisante
- **WHEN** un service demande plus de mémoire qu'aucun nœud n'en a
- **THEN** il reste `Pending` avec la condition `Scheduled=False` et le message du scheduler
