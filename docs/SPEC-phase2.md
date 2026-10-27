# AAFly — Spécification phase 2 : corrections REA + compatibilité AAItems

Complète `SPEC.md` (qui reste la référence pour tout ce qui n'est pas modifié ici). En cas de contradiction, **ce document gagne**.
Sources : `docs/private/REA.md` (local, non versionné) (analyse comportementale de wFly 1.0.5.2 fournie par Dylan) et le code d'AAItems (`Ruza Drive\Synchro inter PC\AAItems`).

Règle clean-room : on reprend des **comportements observables**, jamais du code. Le dossier de preuves (désassemblage du jar wFly) n'entre **pas** dans le dépôt et n'est pas transmis aux implémenteurs.

## A. Corrections issues de la rétro-ingénierie (anomalies wFly à ne pas reproduire)

### A1. Composition logique des conditions (P0)
- Une règle = conditions en **AND** (inchangé).
- Nouveaux types composites : `any` (alias `or`) et `all` (alias `and`), clé `children:` (map nom → condition, même grammaire, récursif, profondeur max 8, 32 enfants max par nœud). `any` vide = **erreur de chargement** (jamais `true` silencieux).
- Négation : préfixe `!` sur le type (`type: "!has_permission"`) **ou** clé `negate: true`.
- Les noms de types sont insensibles à la casse (`STRING_EQUALS` = `string_equals`) pour accepter les configs wFly.
- `string_equals*` / `string_contains*` : placeholders appliqués à `input` **et** à `value`.
- Tests JUnit : AND, OR, NOT, imbrication, `any` vide refusé, profondeur dépassée refusée.

### A2. Priorité réellement respectée (P0)
Tri stable par `priority` croissante puis ordre de déclaration. Aucune limite du nombre de règles.

### A3. Atterrissage sûr (P0) — `SafeLanding`
1. Scan vers le bas depuis la position du joueur, au plus `flight.safe-landing.max-scan` blocs (défaut 64), sans descendre sous `world.getMinHeight()`.
2. Bloc porteur : solide et non dangereux. Refusés : lave, feu, feu des âmes, cactus, buisson de baies, magma, poudreuse, campfire allumé, pointed dripstone, eau profonde comptée comme non porteuse (l'eau n'est pas mortelle : accepter l'eau comme repli si aucun sol sûr).
3. Pieds et tête passables (`isPassable()` et non liquide dangereux).
4. Repli 1 : dernière position sûre mémorisée (mise à jour par MoveListener quand le joueur est au sol, au plus 1 fois/s).
5. Repli 2 : `world.getSpawnLocation()` sécurisé par la même routine.
6. Toujours : yaw/pitch conservés, `fallDistance = 0`, flag anti-dégâts de chute 5 s.

### A4. Exemptions de décrément
Pas de décrément si : illimité, **spectateur**, créatif (le vol y est natif), bypass (si `rules.bypass-no-decrement`). OP **n'est pas** une exemption implicite (`flight.unlimited-for-op`, défaut false) — divergence volontaire, plus sûre.

### A5. Cycle de vie et concurrence (P0)
- Executor I/O possédé par le plugin : `shutdown()` → `awaitTermination(10 s)` → `shutdownNow()`.
- État joueur : uniquement main thread ; l'I/O reçoit des snapshots immuables. Aucune `HashMap` partagée entre threads (sinon `ConcurrentHashMap` justifiée).
- Le dirty flag n'est effacé **qu'après** succès de l'écriture (si l'écriture échoue, il reste dirty et sera retenté au flush suivant, avec backoff borné).
- Colonne `version BIGINT` (verrou optimiste) : l'upsert MySQL n'écrase pas une ligne plus récente écrite par un autre serveur (`WHERE version = ?`, en cas de conflit : relire, ré-appliquer le delta en mémoire, retenter 3 fois max, puis log WARN).
- Table `aafly_server(key VARCHAR(64) PK, value BIGINT)` pour l'heure d'arrêt (`shutdown_at`) — pas de timestamp en clé primaire.

### A6. PvP / proximité (P1, perf)
Pas de travail sur `PlayerMoveEvent` en dehors du filtre « changement de bloc » ; vérification throttlée par le ticker (`pvp.check-interval-seconds`), valeurs des placeholders bypass mises en cache 2 s par joueur. Bloquer aussi `PlayerToggleFlightEvent` si une menace est proche. Interface `ThreatResolver` injectable (API).

### A7. Divers
- Version des placeholders = version du plugin (`getPluginMeta().getVersion()`), jamais codée en dur.
- Aucune suppression ni réécriture destructive de la configuration utilisateur : clés inconnues → WARN, clés manquantes → ajoutées.
- Aucun appel réseau, aucune télémétrie, aucune publicité.
- Actions **compilées au chargement** (enum `ActionType`, sons validés au reload), logs avec `ruleId` + action + cause.

### A8. Seuils de temps restant
`messages.yml › commands-time-remaining` accepte deux formes : map `seuil → liste d'actions` (`[title]`, `[sound]`, `[message]`, `[actionbar]`, `[command]`) **et** la forme héritée wFly `seuil → { commands: "..." }` (convertie en `[command] ...`).

### A9. Migration depuis wFly et TempFly
Commande console uniquement : `/aafly migrate wfly [sqlite|mysql] [--dry-run]` et `/aafly migrate tempfly [--dry-run]` (permission `aafly.admin.migrate`).
- wFly SQLite : `plugins/wFlyV2/database.db`, table `fly` (`uniqueId`, `isinFly`, `FlyTimeRemaining`, `lastUpdate`). wFly MySQL : mêmes colonnes, connexion lue dans `plugins/wFlyV2/config.yml` (section `mysql`), jamais loggée.
- Source ouverte en **lecture seule** (SQLite `?mode=ro` / `SET SESSION TRANSACTION READ ONLY`), jamais modifiée.
- Import dans **une transaction**, validation de chaque ligne (UUID valide, temps ≥ 0 borné par `limits.max-seconds`), lignes invalides comptées et ignorées.
- Rapport : lus / importés / ignorés / déjà présents (stratégie `--merge=max|replace|skip`, défaut `max`). `--dry-run` n'écrit rien.
- Exécution hors main thread, résultat renvoyé sur le main thread ; profils en ligne rechargés après import.
- TempFly : lecture du fichier de données TempFly (`plugins/TempFly/data.yml` : clé `players.<uuid>.time` en secondes) en lecture seule ; si le format n'est pas reconnu, message clair et aucune écriture.

### A10. Redis (optionnel, désactivé par défaut)
Hors périmètre v1 : la clé `redis:` d'une config wFly est ignorée avec WARN. Le multi-serveur passe par MySQL + verrou optimiste (A5). (Pas de lib Jedis embarquée.)

## B. Compatibilité AAItems

### B1. Problème
AAItems possède son propre vol temporisé (effet `flight_time` : plumes, consommables) qui pilote `setAllowFlight`. Si AAFly et AAItems tournent ensemble, chacun peut retirer le vol accordé par l'autre. Il faut **un seul propriétaire** du vol par joueur, et permettre aux items AAItems de créditer le solde AAFly.

### B2. Côté AAFly : API publique stable `com.azurhosts.aafly.api`
Exposée via `Bukkit.getServicesManager()` (`AAFlyApi.class`) **et** `AAFlyProvider.get()` (lève `IllegalStateException` si non chargé). Toutes les méthodes : **main thread uniquement** (lèvent `IllegalStateException` sinon), aucune ne bloque.

```java
public interface AAFlyApi {
    ApiVersion apiVersion();                          // record (major, minor, patch) ; v1.0.0 ; isCompatibleWith(required)
    long remainingSeconds(UUID player);               // -1 si profil non chargé
    boolean isFlightEnabled(UUID player);
    boolean isUnlimited(Player player);
    boolean isSuspended(UUID player);                 // coupé par règle / menace
    GrantResult grant(Player player, GrantRequest request);   // crédit contrôlé (voir B3)
    boolean enable(Player player, String source);     // respecte règles et solde
    boolean disable(Player player, String source);
    void registerCondition(String type, ConditionFactory factory);   // extension du moteur de règles
    void registerThreatResolver(ThreatResolver resolver);
}
public record GrantRequest(long seconds, GrantMode mode, long capSeconds, boolean autoEnable, String source) {}
public enum GrantMode { EXTEND, REFRESH, DENY_IF_ACTIVE }
public record GrantResult(Status status, long remainingSeconds) {
    public enum Status { GRANTED, ALREADY_ACTIVE, CAP_REACHED, DENIED_BY_RULE, UNLIMITED, NOT_LOADED, DISABLED_BY_CONFIG }
}
```
Sémantique de `grant` (calquée sur `FlightRules` d'AAItems, en secondes) :
- cap effectif = `min(request.capSeconds, limits.max-seconds)` ; `seconds` ∈ [1, cap] sinon `IllegalArgumentException`.
- `EXTEND` : `min(courant + seconds, cap)` ; rien ajouté → `CAP_REACHED`.
- `REFRESH` : `max(courant, seconds)` ; rien ajouté → `ALREADY_ACTIVE`.
- `DENY_IF_ACTIVE` : vol activé **ou** solde > 0 → `ALREADY_ACTIVE`, sinon crédite `seconds`.
- Joueur illimité → `UNLIMITED` (rien crédité : l'appelant rembourse ses coûts).
- Profil pas encore chargé (join en cours) → `NOT_LOADED`.
- `api.allow-external-grants: false` dans la config → `DISABLED_BY_CONFIG`.
- `autoEnable` : si true et que les règles l'autorisent, active le vol après crédit ; si une règle refuse, le temps **reste crédité**, statut `GRANTED`, vol non activé (message `not-allowed-here`).
- Émet `FlightTimeChangeEvent` (cancellable : annulé → `DENIED_BY_RULE`, rien crédité) avec `source` ; journalise au niveau DEBUG `source`, joueur, delta.
- `source` : `^[a-z0-9_.:-]{1,32}$` sinon `IllegalArgumentException`.

Livrable : la tâche Gradle `apiJar` produit `build/libs/AAFly-api-<version>.jar` contenant **uniquement** `com/azurhosts/aafly/api/**` (aucune dépendance runtime hors Paper API), pour que des plugins tiers compilent contre l'API sans embarquer AAFly. Javadoc complète sur le package `api`.

### B3. Côté AAFly : coexistence
- AAFly ne retire **que le vol qu'il a lui-même accordé** : marqueur en mémoire `ownsFlight(uuid)`. À la désactivation, s'il ne possède pas le vol, il ne touche ni `allowFlight` ni `flying`.
- Si un autre plugin a déjà mis `allowFlight=true` quand le joueur fait `/fly`, AAFly prend la main normalement (et devient propriétaire) ; il n'essaie pas de deviner qui l'avait accordé.
- Config :
```yaml
api:
  allow-external-grants: true
integrations:
  aaitems:
    enabled: true            # si AAItems est présent, affiche dans /aafly about l'état de la délégation
```
- `/aafly about` liste les intégrations détectées (PlaceholderAPI, LuckPerms, AAItems) et l'état de l'API.

### B4. Côté AAItems : module `aaitems-integration-aafly` (dépôt AAItems, branche `feat/integration-aafly` depuis `main`)
Respecte **toutes** les conventions AAItems (`docs/dev/CONTRIBUTING.md`, SPEC-phase3/4, modules d'intégration chargés par nom de classe seulement si le plugin est actif, API tierce en `compileOnly`, jar épinglé par SHA-256 comme `libs/aaengine`, tests JUnit 4 + hamcrest, commentaires FR, en-tête « Developped by AzurHOSTS.com and powered by AzurWare.fr. »).
1. `aaitems-api` : `ProviderKind.FLIGHT("flight")` + interface `FlightProvider` (dans le package des fournisseurs d'AAItems, pur Java) : `String id()`, `FlightResult grant(Actor, FlightGrant)` — réutiliser les types core existants (ou équivalents API si la règle de dépendance api→core l'interdit : dans ce cas définir le contrat dans core comme `FlightPort`, et l'enregistrement via `ProviderRegistry`).
2. `aaitems-core` : réglage `tools.flight.provider: auto | native | <id>` (défaut `auto`) validé par le validateur positionné ; `auto` = fournisseur `aafly` s'il est READY, sinon natif. Un `flight_time` n'est **jamais** servi gratuitement : fournisseur demandé mais absent → `UNAVAILABLE` (coûts remboursés), avertissement au chargement, ligne dans `/aai status`. Documentation générée (BuiltinDocs/ConfigSchema) mise à jour.
3. `aaitems-platform-v26` : `PaperPlatformBridge.flight()` renvoie un `FlightPort` délégant selon le réglage. Quand le fournisseur externe est actif, le `FlightService` natif ne doit plus jamais retirer un vol qu'il n'a pas accordé (déjà le cas : vérifier et tester).
4. `aaitems-integration-aafly` : `AAFlyIntegration implements IntegrationModule` (sonde `AAFlyProvider.get().apiVersion()` compatible 1.0.0, sinon module BROKEN), `AAFlyFlightProvider` :
   - ticks → secondes : `ceil(ticks / 20)` ; cap idem ;
   - `EXTEND→EXTEND`, `REFRESH→REFRESH`, `DENY→DENY_IF_ACTIVE`, `autoEnable=true`, `source="aaitems"` ;
   - `worlds` non vide → `WORLD_DENIED` si le monde courant n'y est pas ; sinon accordé mais **avertissement de validation** au chargement (« avec le fournisseur aafly, la restriction de mondes ne s'applique qu'à l'utilisation ; utilisez les fly-rules d'AAFly ») ;
   - correspondance des statuts : `GRANTED→GRANTED`, `ALREADY_ACTIVE→ALREADY_ACTIVE`, `CAP_REACHED→CAP_REACHED`, `UNLIMITED→ALREADY_ACTIVE` (rien accordé, remboursé), `DENIED_BY_RULE→UNAVAILABLE`, `DISABLED_BY_CONFIG→UNAVAILABLE`, `NOT_LOADED→OFFLINE` ; `remainingTicks = remainingSeconds*20` (saturé) ;
   - toute exception/LinkageError de l'API AAFly → `UNAVAILABLE` + log, jamais propagée.
   - `libs/aafly/` : `AAFly-api-1.0.0.jar` + `SHA256SUMS` + `LISEZMOI.md` (même mécanisme de vérification que `libs/aaengine`).
5. `aaitems-plugin` : module inclus dans le jar final, `softdepend: [AAFly]` (ou `paper-plugin.yml` équivalent), `verifyShadowJar` exige ses classes et refuse `com/azurhosts/aafly/**` dans le jar.
6. Docs : `docs/admin/integrations.md` (section AAFly), README (tableau des modules : 19 intégrations), `docs/dev/ETAT-*` si pertinent.
7. Tests : correspondance de statuts, conversion ticks/secondes (arrondi, saturation), choix du fournisseur (`auto` avec/sans AAFly, `native`, id inconnu), refus sans service gratuit.

### B5. Exemple d'item AAItems utilisant AAFly
```yaml
# items/plume_de_vol.yml (AAItems)
plume_de_vol:
  material: FEATHER
  name: "<aqua>Plume de vol"
  abilities:
    - trigger: right_click
      consume: 1
      effects:
        - type: flight_time
          duration: 10m
          stack: extend
          max-total: 2h
```
Avec AAFly installé, cette plume crédite 10 min sur le solde AAFly (visible par `/flytime` et `%aafly_remaining%`) et active le vol si les règles AAFly l'autorisent.
