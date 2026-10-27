# AAFly — Spécification d'architecture (clean-room)

AAFly est un plugin Paper de vol temporisé, conçu par AzurHOSTS. Il reproduit le périmètre fonctionnel
de wFly (voir ../WFLY_FEATURES.md) **sans aucun code de wFly** (source inaccessible, reproduction à notre manière),
avec deux priorités : **sécurité** et **performance**.

## 1. Stack
- Java 25 toolchain (code compatible Java 21 : records, switch patterns, sealed OK ; pas d'API 22+ exotique).
- Paper API `26.3.build.224-stable` (compileOnly). Serveurs cibles : Paper 1.21+ / 26.x. Pas de support Spigot 1.8 (choix assumé : Paper moderne, Adventure).
- Gradle 9.8.1 (Kotlin DSL), wrapper identique à AAEngine, version catalog `gradle/libs.versions.toml`.
- Dépendances runtime chargées via **Paper PluginLoader** (`paper-plugin.yml` + `MavenLibraryResolver`), pas de shading :
  - `com.zaxxer:HikariCP` (pool), `org.xerial:sqlite-jdbc`, `com.mysql:mysql-connector-j`
  - `org.spongepowered:configurate-yaml:4.2.0` (config typée)
- Soft-depends : `PlaceholderAPI`, `LuckPerms`.
- Tests : JUnit Jupiter (BOM 6.1.3) pour la logique pure (parser temps, formatter, moteur de règles, signature token, TimeMath).
- Package racine : `com.azurhosts.aafly`. Group `com.azurhosts`, artifact `aafly`, version `1.0.0`.

## 2. Arborescence des packages
```
com.azurhosts.aafly
├── AAFlyPlugin            (JavaPlugin : wiring, lifecycle, reload)
├── AAFlyLoader            (PluginLoader : libs Maven)
├── api/                   FlightApi (façade publique, Bukkit ServicesManager), events/ (FlightToggleEvent, FlightTimeChangeEvent, FlightExpireEvent — Cancellable quand pertinent)
├── config/                Settings (records Configurate), MessagesConfig, ConfigManager (load/validate/reload, ajout des clés manquantes), Lang (MiniMessage + legacy & + hex)
├── time/                  DurationParser "1d2h30m15s" & entiers (secondes), TimeFormatter (auto-format, null-values, unités activables, unlimited), TimeMath (saturation)
├── core/                  FlightProfile, FlightService, FlightTicker (1 tâche / seconde), SafeLanding, FlySpeedService, DecrementPolicy
├── storage/               ProfileRepository (interface), SqliteProfileRepository, MySqlProfileRepository, StorageManager (cache + dirty-set + flush périodique + save async au quit), SchemaMigrator
├── rules/                 Condition (interface), ConditionRegistry, conditions/*, Rule, RuleSet, RuleEngine, RuleContext, NearbyPlayerChecker
├── action/                Action (interface), ActionParser, ActionExecutor, TimeThresholdNotifier (commands-time-remaining)
├── placeholder/           PlaceholderBridge (parse PAPI si présent, sinon no-op), AAFlyExpansion (préfixe aafly), WFlyCompatExpansion (préfixe wfly, activable)
├── token/                 FlyTokenService (création/redeem), TokenSigner (HMAC-SHA256, secret local), TokenListener
├── timedfly/              TimedFlyService (temps par groupe + cooldown), GroupResolver (LuckPerms si présent, sinon permissions aafly.group.<nom>)
├── command/               FlyCommand (/fly), AAFlyCommand (/aafly), FlySpeedCommand, FlyTimeCommand, CommandUtil (parse joueur/temps, flag -s, tab-complete)
├── listener/              ConnectionListener, WorldChangeListener, GameModeListener, FallDamageListener, MoveListener (détection immobilité)
└── hook/                  LuckPermsHook (optionnel), PapiHook
```

## 3. Modèle de données
`FlightProfile` (mutable, confiné au main thread, snapshot immuable `FlightProfileSnapshot` pour la persistance) :
- `UUID uuid`, `String lastKnownName`
- `long remainingSeconds` (≥ 0, borné par `limits.max-seconds`, défaut 10 ans) — saturation via TimeMath, jamais d'overflow
- `boolean flightEnabled` (état voulu par le joueur, persisté : réactivé à la reconnexion)
- `long lastUpdateEpochSeconds` (pour le décrément hors-ligne)
- `long cooldownUntilEpochSeconds` (timed-fly)
- `double flySpeed` (0.1–1.0, persistée)
- `boolean dirty` (non persisté)

Table SQL `aafly_profiles` (uuid CHAR(36) PK, name VARCHAR(16), remaining_seconds BIGINT, flight_enabled BOOLEAN, last_update BIGINT, cooldown_until BIGINT, fly_speed DOUBLE) + `aafly_schema(version INT)`.
Table `aafly_tokens` (token_id CHAR(36) PK, seconds BIGINT, created_by CHAR(36), created_at BIGINT, redeemed_by CHAR(36) NULL, redeemed_at BIGINT NULL) — anti-duplication : un token ne peut être encaissé qu'une fois (`UPDATE ... WHERE token_id=? AND redeemed_at IS NULL`, vérifier rowcount = 1).

**Toutes** les requêtes sont préparées (paramètres liés). Upsert (`INSERT ... ON CONFLICT` SQLite / `ON DUPLICATE KEY UPDATE` MySQL). Aucune concaténation de valeurs dans le SQL.

## 4. Services
### FlightService (main thread)
- `enable(player, cause)`, `disable(player, cause)`, `toggle`, `isEnabled`, `evaluate(player) -> RuleDecision`
- `addTime(uuid, seconds, actor)`, `removeTime`, `setTime`, `reset`, `addAll/removeAll(onlinePlayers)`
- `hasUnlimited(player)` : permission `aafly.unlimited` (alias `wfly.infinite.fly` si `compat.wfly-permissions: true`) ; OP n'est **pas** illimité par défaut (`flight.unlimited-for-op: false`).
- À l'enable : vérifie temps > 0 ou illimité, évalue RuleEngine, `setAllowFlight(true)`, `setFlying(true)` si en l'air, applique la vitesse.
- À la disable : `setAllowFlight(false)` sauf gamemode creative/spectator, SafeLanding si `flight.tp-on-floor-when-disabled` (bloc solide le plus proche sous le joueur, garde yaw/pitch, flag no-fall-damage consommé par FallDamageListener, expirant après 5 s).
- Causes : PLAYER, ADMIN, EXPIRED, RULE, NEARBY, WORLD_CHANGE, GAMEMODE, TIMED_FLY, API. GAMEMODE et TIMED_FLY sont réservées à l'API (jamais émises par AAFly : un changement de mode de jeu ne coupe pas le vol, l'expiration timed-fly utilise EXPIRED) ; `fly.gamemode-change` sert quand un plugin tiers désactive avec GAMEMODE.
- Émet les events API.

### FlightTicker (1 BukkitTask synchrone / 20 ticks)
Pour chaque joueur en ligne avec `flightEnabled` :
1. Illimité → pas de décrément, mais règles ré-évaluées tous les `rules.check-interval-seconds` (défaut 1).
2. DecrementPolicy : `FLY_MODE` (décrémente dès que le vol est activé) ou `FLYING_MODE` (seulement si `player.isFlying()`), + `decrement.disable-when-static` (aucun mouvement depuis `static-delay-seconds` → pas de décrément ; position bloc mémorisée par MoveListener, aucun calcul coûteux).
3. `decrement-rules` (mêmes conditions, sans result) → si une règle matche, pas de décrément.
4. Décrémente de 1, marque dirty, déclenche TimeThresholdNotifier si le reste correspond à un seuil configuré (une fois par passage).
5. Reste = 0 → disable(EXPIRED), actions `on-expire`.
6. `fly-rules` : DENY → disable(RULE) et mémorisation `suspendedByRule` ; ALLOW ultérieur → re-enable silencieux (aucun message ni action) si `flight.auto-reactivate-after-rule`.
7. Action bar (si activé) : message formaté, `hide-if-unlimited`.
Optimisation : placeholders PAPI résolus seulement si au moins une règle en utilise ; dernière décision par joueur mise en cache pendant `check-interval`.

### Décrément hors-ligne (`decrement.offline: true`)
À la connexion : `elapsed = now - lastUpdate` ; si le profil avait `flightEnabled` → soustraire `elapsed` (saturé à 0). Idem cooldown timed-fly si `timed-fly.decrement-cooldown-offline`.

### TimedFlyService
`timed-fly.enabled` : groupes ordonnés (liste, premier match gagne) avec `fly-time` et `cooldown`. Quand activé : add/remove admin refusés (message), temps rechargé à `fly-time` quand le cooldown est écoulé, `reset-after-disable` optionnel. GroupResolver : LuckPerms primary group si présent (seul critère), sinon permission `aafly.group.<name>` ; `aafly.group.default` est déclarée `default: true` (groupe `default` pour tous sans LuckPerms). Aucun groupe → `timed-fly.no-group`.

### Nearby / PVP
`pvp.enabled`, `radius` (1–64, validé), `bypass-placeholders` : pour chaque joueur en vol, `getNearbyPlayers(radius)` ; exclure ceux qui ont la même valeur pour **tous** les placeholders bypass (même land/faction) ; choix sûr : une valeur vide ou non résolue (ou PlaceholderAPI absent) ne rend jamais deux joueurs alliés. Ennemi proche → disable(NEARBY) + suspendu, re-enable quand libre (indépendamment de `flight.auto-reactivate-after-rule`). La détection PVP s'applique aussi aux joueurs `aafly.bypass.rules` (fonction distincte des règles). Fréquence `pvp.check-interval-seconds` (défaut 1), uniquement si activé.

## 5. Règles (rules/)
```yaml
fly-rules:
  admin_bypass:
    priority: 0
    conditions:
      perm: { type: has_permission, permission: "aafly.admin" }
    result: ALLOW
  no_combat:
    priority: 1
    conditions:
      c: { type: string_equals, input: "%combatlogx_in_combat%", value: "true" }
    result: DENY
    actions: ["[actionbar] <red>Combat en cours"]
  default:
    priority: 999
    result: ALLOW
```
- Tri par priorité croissante ; la première règle dont **toutes** les conditions matchent donne le résultat ; aucune règle → ALLOW.
- `ConditionRegistry` extensible (API) : `string_equals`, `string_equals_ignorecase`, `string_contains`, `string_contains_ignorecase`, `string_regex` (pattern compilé au chargement, entrée tronquée à 256 chars), `number_equals`, `number_greater`, `number_less`, `number_between` (parse Double sûr), `has_permission`, `world` (liste), `gamemode`.
- `input` est passé à PlaceholderBridge (PAPI) puis comparé ; jamais exécuté.
- Chargement : règle invalide → log WARN explicite (nom, raison) et règle ignorée ; jamais de crash.
- Permission `aafly.bypass.rules` : ignore les règles (et pas de décrément si `rules.bypass-no-decrement: true`).

## 6. Actions (action/)
Format `"[type] contenu"`. Types : `message`, `actionbar`, `title` (`titre;sous-titre`), `subtitle`, `command` (console), `player` (commande exécutée par le joueur **uniquement** si `actions.allow-player-commands: true`), `sound` (`NAME[;volume;pitch]`).
Substitutions : `%player%` (nom validé `^[A-Za-z0-9_]{1,16}$`, sinon action ignorée + WARN), `%uuid%`, `%remaining%` (durée formatée, identique pour toutes les listes d'actions ; tags retirés dans une commande), `%remaining_seconds%` (entier de secondes, pour les commandes ; absent → action ignorée), puis PAPI si présent. Pas d'autre interpolation. Les commandes console viennent **uniquement** de la config.

`commands-time-remaining` (messages.yml) : map seuil(secondes) → liste d'actions. Déclenché une fois par passage du seuil.

## 7. Tokens de vol (token/)
`/fly convert <temps>` : retire le temps au joueur (vérif solde, min/max, cooldown), crée une ligne `aafly_tokens`, donne un item (matériau, nom, lore, custom-model-data configurables) avec PDC : `aafly:token_id` (UUID), `aafly:seconds` (long), `aafly:sig` = HMAC-SHA256(secret, token_id + "|" + seconds) en hex.
Secret : fichier `plugins/AAFly/token.secret` (32 octets `SecureRandom`, créé au premier lancement, permissions 600 si POSIX).
Redeem (clic droit avec l'item) : signature vérifiée en temps constant (`MessageDigest.isEqual`), puis UPDATE conditionnel en DB (anti-dupe, async), puis crédit sur main thread ; l'item est consommé **après** succès DB (1 unité). Échec → message, item intact.

## 8. Commandes & permissions
| Commande | Permission | Notes |
|---|---|---|
| /fly | aafly.fly | toggle ; alias config `commands.fly-aliases` |
| /fly help | aafly.fly | |
| /fly time [joueur] | aafly.time / aafly.time.others | |
| /fly give <joueur> <temps> | aafly.give | depuis son propre solde ; `give.cooldown-seconds`, `give.min/max-seconds`, pas à soi-même |
| /fly convert <temps> | aafly.token | |
| /flyspeed <1-10> | aafly.speed, plafond `aafly.speed.<n>` | 1-10 mappé sur 0.1-1.0 |
| /flytime | aafly.time | |
| /aafly add\|remove\|set\|reset <joueur> [temps] [-s] | aafly.admin.time | joueur hors-ligne accepté si déjà connu (profil en DB), sinon refus |
| /aafly addall\|removeall <temps> [-s] | aafly.admin.time.all | joueurs en ligne |
| /aafly activate\|deactivate\|toggle <joueur> | aafly.admin.toggle | |
| /aafly status <joueur> | aafly.admin.status | |
| /aafly convert <joueur> <temps> | aafly.admin.token | crée un token sans débiter |
| /aafly reload | aafly.admin.reload | config + messages + règles ; stockage non rechargé (log explicite) |
| /aafly about | aafly.admin.about | version, stockage, stats ticker |
| /aafly help | aafly.admin | |
Permissions spéciales : `aafly.unlimited`, `aafly.bypass.rules`, `aafly.group.<g>`. Compat optionnelle `compat.wfly-permissions` mappe `wfly.infinite.fly` → unlimited, `wfly.bypass.fly` → bypass.
Temps : entier = secondes ; ou `1d2h3m4s`. Toute valeur ≤ 0 ou > `limits.max-seconds` refusée. Tab-complete pour tout. Flag `-s` = silencieux pour la cible.

## 9. Placeholders (PAPI optionnel)
`%aafly_remaining%` (formaté), `%aafly_remaining_seconds|minutes|hours|days%` (entier ; `unlimited` si illimité), `%aafly_enabled%`, `%aafly_flying%`, `%aafly_unlimited%`, `%aafly_speed%`, `%aafly_cooldown%`.
Expansion compat `wfly` (si `compat.wfly-placeholders: true`) : `%wfly_fly_remaining%`, `%wfly_fly_activate%`, `%wfly_fly_remaining_<unit>%`.

## 10. Config (config.yml, valeurs par défaut)
```yaml
config-version: 1
debug: false
storage:
  type: SQLITE            # SQLITE | MYSQL
  save-interval-seconds: 300
  mysql: { host: localhost, port: 3306, database: aafly, username: aafly, password: "", use-ssl: false, pool-size: 6 }
limits:
  max-seconds: 315360000  # 10 ans
decrement:
  mode: FLYING_MODE       # FLY_MODE | FLYING_MODE
  disable-when-static: false
  static-delay-seconds: 3
  offline: false
flight:
  tp-on-floor-when-disabled: true
  auto-reactivate-after-rule: true
  unlimited-for-op: false
  keep-flight-on-world-change: true
  default-speed: 0.1
actionbar:
  enabled: false
  hide-if-unlimited: true
  message: "<gold>Vol : <yellow>%aafly_remaining%"
rules:
  check-interval-seconds: 1
  bypass-no-decrement: true
pvp:
  enabled: false
  radius: 5
  check-interval-seconds: 1
  bypass-placeholders: []
give:
  enabled: true
  cooldown-seconds: 0
  min-seconds: 5
  max-seconds: 3600
token:
  enabled: true
  material: FEATHER
  name: "<aqua>Jeton de vol <gray>(%remaining%)"
  lore: ["<gray>Clic droit pour encaisser", "<dark_gray>AAFly by AzurHOSTS"]
  custom-model-data: 0
  min-seconds: 60         # bornes de /fly convert
  max-seconds: 86400
  cooldown-seconds: 10
timed-fly:
  enabled: false
  decrement-cooldown-offline: false
  reset-after-disable: false
  groups:
    - { name: vip, fly-time: 120, cooldown: 30 }
    - { name: default, fly-time: 10, cooldown: 60 }
format:
  seconds: true
  minutes: true
  hours: true
  days: true
  auto-format: true
  unlimited: "Illimité"
  empty: "0s"
  pattern: "%days% %hours% %minutes% %seconds%"
  units:
    seconds: "<yellow>%seconds%<gold>s"
    minutes: "<yellow>%minutes%<gold>m"
    hours: "<yellow>%hours%<gold>h"
    days: "<yellow>%days%<gold>j"
actions:
  allow-player-commands: false
compat:
  wfly-placeholders: true
  wfly-permissions: true
commands:
  fly-aliases: []
  toggle-cooldown-millis: 1000   # anti-spam /fly (0–60000, 0 = désactivé)
fly-rules:
  default: { priority: 999, result: ALLOW }
decrement-rules: {}
```
`messages.yml` : tous les messages (MiniMessage, `<prefix>`), `commands-time-remaining`, `on-enable`, `on-disable`, `on-expire`, `not-allowed-here`. Clés manquantes → ajoutées avec valeurs par défaut au chargement (sans écraser l'existant). Config invalide → le plugin se désactive proprement avec un message clair (jamais d'état à moitié chargé).

## 11. Règles de sécurité (non négociables)
1. SQL : PreparedStatement uniquement ; identifiants fixes ; pool Hikari ; mot de passe jamais loggé.
2. Aucun accès Bukkit API hors main thread ; l'I/O passe par un `ExecutorService` dédié (threads nommés `AAFly-IO`), retour sur main thread via `Bukkit.getScheduler().runTask`. Arrêt propre : flush synchrone au `onDisable` avec timeout 10 s.
3. Entrées utilisateur : noms (`^[A-Za-z0-9_]{1,16}$`), temps bornés, vitesse bornée, jamais insérées dans une commande ni dans du MiniMessage non échappé (`Component.text` pour les valeurs dynamiques).
4. Tokens signés HMAC + nonce DB ; secret non versionné (`.gitignore`), jamais loggé.
5. Permissions vérifiées sur chaque sous-commande ; console autorisée sur les commandes admin uniquement.
6. Pas de téléchargement, pas d'updater réseau, pas de métriques (aucune lib bStats en v1).
7. Overflow : `TimeMath` (saturation à `max-seconds`, jamais négatif).
8. Logs : jamais de PII au-delà nom/UUID ; niveau DEBUG via `debug: false`.

## 12. Performance
- Un seul ticker, O(joueurs en vol). Pas de tâche async par joueur.
- Cache mémoire des profils en ligne ; profils hors-ligne chargés à la demande puis évincés (LRU 256 + expiration 10 min).
- Écriture batchée (dirty-set) toutes les `save-interval-seconds` + au quit (async) + au shutdown (sync).
- Placeholders PAPI évalués seulement si nécessaires ; décisions de règles mises en cache 1 s.
- Aucune réflexion / NMS.

## 13. Qualité
- `./gradlew build` vert (compile + tests, `-Xlint:all,-processing`).
- Tests unitaires : DurationParser, TimeFormatter, TimeMath, RuleEngine (priorité, conditions), ActionParser, TokenSigner.
- README.md (FR) : installation, config, commandes, permissions, placeholders, migration depuis wFly (tableau de correspondance), crédits « Developped by AzurHOSTS.com and powered by AzurWare.fr. ». LICENSE : MIT, copyright AzurHOSTS (Hugo CORBIN, président).
- CI GitHub Actions : build + tests sur push/PR.

## 14. Décisions d'implémentation phase 2 (complète `SPEC-phase2.md`)
- **API** : `api/FlightApi` (phase 1) est remplacée par `api/AAFlyApi` (SPEC-phase2 B2), seule API publique ; son implémentation vit dans `apiimpl/` pour que `AAFly-api-<version>.jar` ne contienne que `com/azurhosts/aafly/api/**` (vérifié par la tâche `verifyApiIsolation`). `DisableCause` passe dans `api`. Méthode ajoutée : `formatTime(long)`. `GrantRequest` valide `seconds ≥ 1`, `capSeconds ≥ 1` et `source` dès la construction.
- **grant** : un listener de `FlightTimeChangeEvent` qui ramène la valeur au niveau courant ou plus bas donne aussi `DENIED_BY_RULE` (l'appelant rembourse). Timed-fly actif → `DISABLED_BY_CONFIG`.
- **Extensions** : un type de condition enregistré (ou retiré à la désactivation de son plugin) déclenche un rechargement des règles au tick suivant. Une condition tierce en erreur (`RuntimeException` ou `LinkageError`) vaut `false`, avertissement unique.
- **Conditions** : un groupe `all` vide est refusé comme `any` vide ; `!` combiné à `negate: true` est refusé (ambigu). La limite de 64 conditions directes par règle est conservée.
- **Atterrissage sûr** : ordre de repli = sol sûr dans la colonne → eau (deux blocs libres au-dessus) → dernière position sûre mémorisée (revérifiée bloc par bloc, jamais via `onGround`) → spawn du monde.
- **Coexistence** : à la connexion, si le profil voulait voler et que `allowFlight` est déjà vrai, AAFly reprend la propriété (cas d'un crash serveur ; empêche un vol gratuit sans solde).
- **Configuration** : clés inconnues signalées par un WARN (jamais supprimées), hors sections libres (`fly-rules`, `decrement-rules`, groupes, seuils).
- **`/aafly about`** : affiche l'état de l'API (version, crédits externes, résolveurs tiers, vols possédés) et les intégrations PlaceholderAPI, LuckPerms, AAItems.
