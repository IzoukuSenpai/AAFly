# AAFly

**Vol temporisé pour serveurs Paper, par [AzurHOSTS](https://azurhosts.com).**

AAFly donne à chaque joueur un temps de vol qui se décompte pendant qu'il vole. Le plugin gère aussi des règles
d'autorisation par zone ou par placeholder, la détection d'ennemis proches, des jetons de vol échangeables, un
mode « timed-fly » par groupe avec recharge, une vitesse de vol par permission et une API pour les autres
plugins. C'est une reproduction *clean-room* du périmètre fonctionnel de wFly : aucune ligne de code de wFly
n'a été reprise. Le plugin a été conçu avec deux priorités, **la sécurité et la performance**.

> Developped by AzurHOSTS.com and powered by AzurWare.fr.

---

## Sommaire

1. [Fonctionnalités](#fonctionnalités)
2. [Prérequis](#prérequis)
3. [Installation](#installation)
4. [Configuration](#configuration-configyml)
5. [Messages et actions](#messages-et-actions-messagesyml)
6. [Commandes](#commandes)
7. [Permissions](#permissions)
8. [Placeholders](#placeholders-placeholderapi)
9. [Règles de vol](#règles-de-vol)
10. [Jetons de vol](#jetons-de-vol)
11. [Mode timed-fly](#mode-timed-fly)
12. [API développeurs](#api-développeurs)
13. [Migration depuis wFly](#migration-depuis-wfly)
14. [Compilation](#compilation)
15. [Sécurité](#sécurité)
16. [Licence et crédits](#licence-et-crédits)

---

## Fonctionnalités

- **Temps de vol par joueur**, stocké en SQLite (par défaut) ou en MySQL (partagé entre plusieurs serveurs).
- **Deux modes de décompte** :
  - `FLY_MODE` : le temps se décompte dès que le vol est activé.
  - `FLYING_MODE` : le temps ne se décompte que lorsque le joueur vole réellement.
  - Pause optionnelle quand le joueur reste immobile.
  - Décompte hors-ligne optionnel.
- **Règles de vol** (`fly-rules`) triées par priorité. Elles reposent sur PlaceholderAPI, les permissions, le
  monde ou le mode de jeu, et peuvent déclencher des actions. Des règles de non-décompte (`decrement-rules`)
  sont aussi disponibles.
- **Réactivation automatique** du vol quand une règle ne l'interdit plus.
- **PvP** : le vol est coupé si un joueur ennemi est proche. Les alliés (même land, même faction) sont
  reconnus via des placeholders.
- **Atterrissage sûr** : à l'arrêt du vol, le joueur est téléporté sur le bloc solide le plus proche sous lui.
  Son orientation est conservée et il ne subit pas de dégâts de chute.
- **Actions** : messages, action bar, titres, sons et commandes, à des seuils de temps et à l'activation,
  la désactivation ou l'expiration.
- **Jetons de vol** : objets signés par HMAC-SHA256 et protégés contre la duplication par un nonce en base de données.
- **Mode timed-fly** : chaque groupe a un temps de vol et une recharge. Les groupes viennent de LuckPerms ou de permissions.
- **Vitesse de vol** de 1 à 10, plafonnée par permission.
- **Messages en MiniMessage**. Les codes legacy `&a` et hexadécimaux `&#RRGGBB` sont aussi acceptés. Les
  messages sont en français par défaut et entièrement personnalisables.
- **Compatibilité wFly** : placeholders `%wfly_...%` et permissions `wfly.*` optionnels.
- **API publique** et événements Bukkit annulables.

## Prérequis

| Élément | Version |
|---|---|
| Serveur | **Paper** 1.21+ / 26.x. Spigot et les versions anciennes ne sont pas pris en charge. |
| Java | 21 ou plus récent |
| PlaceholderAPI | Optionnel (placeholders et règles) |
| LuckPerms | Optionnel (groupes du mode timed-fly) |

Le jar ne contient aucune bibliothèque. Paper télécharge au premier démarrage HikariCP, sqlite-jdbc,
mysql-connector-j et Configurate depuis Maven Central, via le chargeur de plugin
(`MavenLibraryResolver`).

## Installation

1. Téléchargez `AAFly-<version>.jar` (onglet *Releases* ou artefact de la CI), puis placez-le dans `plugins/`.
2. *(Optionnel)* Installez PlaceholderAPI et/ou LuckPerms.
3. Démarrez le serveur. AAFly crée `plugins/AAFly/` avec ces fichiers :
   - `config.yml` : réglages ;
   - `messages.yml` : messages et listes d'actions ;
   - `token.secret` : secret de signature des jetons. **Ne le partagez jamais.**
   - `aafly.db` : base SQLite, si le stockage SQLite est utilisé.
4. Adaptez la configuration, puis lancez `/aafly reload`.

> Une configuration invalide **désactive proprement** le plugin. Un message clair dans la console indique la
> clé fautive. Les clés manquantes sont ajoutées automatiquement, sans écraser vos valeurs.

Pour passer de SQLite à MySQL, modifiez `storage.type` puis **redémarrez** le serveur. Le stockage n'est pas
rechargé à chaud.

## Configuration (`config.yml`)

```yaml
config-version: 1
debug: false                    # journaux de débogage

storage:
  type: SQLITE                  # SQLITE (fichier local) | MYSQL (multi-serveurs)
  save-interval-seconds: 300    # écriture groupée des profils modifiés (10 à 86400)
  mysql:
    host: localhost
    port: 3306
    database: aafly
    username: aafly
    password: ""                # jamais affiché dans les logs
    use-ssl: false              # recommandé à true si la base est distante
    pool-size: 6

limits:
  max-seconds: 315360000        # temps maximal stocké par joueur (10 ans)

decrement:
  mode: FLYING_MODE             # FLY_MODE : dès que le vol est activé ; FLYING_MODE : seulement en vol
  disable-when-static: false    # pas de décompte si le joueur est immobile...
  static-delay-seconds: 3       # ...depuis ce nombre de secondes
  offline: false                # décompter le temps passé hors-ligne si le vol était actif

flight:
  tp-on-floor-when-disabled: true   # atterrissage sûr, sans dégâts de chute
  auto-reactivate-after-rule: true  # réactive le vol quand la règle ne l'interdit plus
  unlimited-for-op: false           # les OP ne sont PAS illimités par défaut
  keep-flight-on-world-change: true
  default-speed: 0.1                # 0.1 à 1.0

actionbar:
  enabled: false
  hide-if-unlimited: true
  message: "<gold>Vol : <yellow>%aafly_remaining%"

rules:
  check-interval-seconds: 1     # fréquence de réévaluation des règles (1 à 60)
  bypass-no-decrement: true     # aafly.bypass.rules : pas de décompte non plus

pvp:
  enabled: false
  radius: 5                     # 1 à 64 blocs
  check-interval-seconds: 1
  bypass-placeholders: []       # ex. ["%lands_land_name%"] : même valeur = alliés

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
  min-seconds: 60              # bornes de /fly convert
  max-seconds: 86400
  cooldown-seconds: 10          # délai entre deux /fly convert

timed-fly:
  enabled: false
  decrement-cooldown-offline: false
  reset-after-disable: false
  groups:                       # ordonnés : le premier groupe correspondant gagne
    - { name: vip, fly-time: 120, cooldown: 30 }
    - { name: default, fly-time: 10, cooldown: 60 }

format:
  seconds: true                 # unités affichées (désactiver une unité ne fait pas perdre de temps)
  minutes: true
  hours: true
  days: true
  auto-format: true             # masque les unités à zéro
  unlimited: "Illimité"
  empty: "0s"
  pattern: "%days% %hours% %minutes% %seconds%"
  units:
    seconds: "<yellow>%seconds%<gold>s"
    minutes: "<yellow>%minutes%<gold>m"
    hours: "<yellow>%hours%<gold>h"
    days: "<yellow>%days%<gold>j"

actions:
  allow-player-commands: false  # autorise l'action [player] (désactivée par sécurité)

compat:
  wfly-placeholders: true       # expansion %wfly_...%
  wfly-permissions: true        # wfly.infinite.fly / wfly.bypass.fly

commands:
  fly-aliases: []               # ex. ["vol", "voler"]

fly-rules:
  default: { priority: 999, result: ALLOW }

decrement-rules: {}
```

### Formats de durée

Une durée se donne soit en **nombre entier de secondes** (`90`), soit en **unités combinées** :
`1d2h30m15s`, `2h`, `45m`. Les unités sont `d` (jours), `h` (heures), `m` (minutes) et `s` (secondes).
Toute durée nulle, négative ou supérieure à `limits.max-seconds` est refusée.

## Messages et actions (`messages.yml`)

Tous les messages sont en MiniMessage et acceptent la balise `<prefix>`. Les valeurs dynamiques (`<player>`,
`<time>`...) sont **toujours échappées** : un pseudo ou un placeholder ne peut pas injecter de mise en forme.
Un message vide (`""`) n'est pas envoyé.

### Listes d'actions

Les listes d'actions sont `on-enable`, `on-disable`, `on-expire`, `not-allowed-here`,
`commands-time-remaining` et les `actions` des règles. Chaque élément s'écrit `"[type] contenu"` :

| Type | Effet | Exemple |
|---|---|---|
| `message` | Message au joueur | `[message] <green>Bon vol !` |
| `actionbar` | Texte en action bar | `[actionbar] <red>Zone interdite` |
| `title` | Titre, et sous-titre optionnel après `;` | `[title] <red>Vol terminé;<gray>Plus de temps` |
| `subtitle` | Sous-titre seul | `[subtitle] <yellow>Atterrissage` |
| `sound` | Son `NOM[;volume;pitch]` | `[sound] BLOCK_NOTE_BLOCK_PLING;1.0;1.0` |
| `command` | Commande **console** | `[command] eco give %player% 10` |
| `player` | Commande exécutée par le joueur, si `actions.allow-player-commands: true` | `[player] spawn` |

Variables disponibles : `%player%`, `%uuid%` et `%remaining%`, puis les placeholders PlaceholderAPI. Un pseudo
non conforme à `^[A-Za-z0-9_]{1,16}$` fait ignorer l'action, et un avertissement est journalisé.

### Seuils de temps restant

```yaml
commands-time-remaining:
  60:
    - "[message] <prefix><yellow>Plus qu'une minute de vol !"
  10:
    - "[message] <prefix><gold>Plus que 10 secondes de vol !"
    - "[sound] BLOCK_NOTE_BLOCK_PLING;1.0;1.0"
```

Chaque seuil (en secondes) se déclenche **une fois par passage**.

## Commandes

Le flag `-s` rend l'action silencieuse pour le joueur ciblé. Toutes les commandes proposent l'autocomplétion.

### Joueurs

| Commande | Permission | Description |
|---|---|---|
| `/fly` | `aafly.fly` | Active ou désactive le vol |
| `/fly help` | `aafly.fly` | Aide |
| `/fly time [joueur]` | `aafly.time` / `aafly.time.others` | Temps restant |
| `/flytime` | `aafly.time` | Raccourci de `/fly time` |
| `/fly give <joueur> <temps>` | `aafly.give` | Donne du temps **depuis son propre solde**. Respecte `give.min-seconds`, `give.max-seconds` et `give.cooldown-seconds`. Impossible de se donner du temps à soi-même. |
| `/fly convert <temps>` | `aafly.token` | Convertit du temps en jeton de vol. Respecte `token.min-seconds`, `token.max-seconds` et `token.cooldown-seconds`. |
| `/flyspeed <1-10>` | `aafly.speed` (+ plafond `aafly.speed.<n>`) | Vitesse de vol (1 à 10 → 0.1 à 1.0) |

### Administration (console autorisée)

| Commande | Permission | Description |
|---|---|---|
| `/aafly add <joueur> <temps> [-s]` | `aafly.admin.time` | Ajoute du temps |
| `/aafly remove <joueur> <temps> [-s]` | `aafly.admin.time` | Retire du temps |
| `/aafly set <joueur> <temps> [-s]` | `aafly.admin.time` | Fixe le temps |
| `/aafly reset <joueur> [-s]` | `aafly.admin.time` | Remet le temps à zéro |
| `/aafly addall <temps> [-s]` | `aafly.admin.time.all` | Ajoute du temps à tous les joueurs en ligne |
| `/aafly removeall <temps> [-s]` | `aafly.admin.time.all` | Retire du temps à tous les joueurs en ligne |
| `/aafly activate <joueur>` | `aafly.admin.toggle` | Force l'activation du vol |
| `/aafly deactivate <joueur>` | `aafly.admin.toggle` | Force la désactivation du vol |
| `/aafly toggle <joueur>` | `aafly.admin.toggle` | Bascule le vol |
| `/aafly status <joueur>` | `aafly.admin.status` | État détaillé d'un joueur |
| `/aafly convert <joueur> <temps>` | `aafly.admin.token` | Crée un jeton **sans débiter** le joueur |
| `/aafly reload` | `aafly.admin.reload` | Recharge la configuration, les messages et les règles (sans le stockage) |
| `/aafly about` | `aafly.admin.about` | Version, stockage et statistiques du ticker |
| `/aafly help` | `aafly.admin` | Aide d'administration |

Un joueur hors-ligne est accepté **s'il est déjà connu**, c'est-à-dire si son profil existe en base. Sinon,
la commande est refusée.

## Permissions

| Permission | Effet |
|---|---|
| `aafly.fly` | Utiliser `/fly` |
| `aafly.time`, `aafly.time.others` | Voir son temps, ou celui d'un autre joueur |
| `aafly.give` | Donner du temps |
| `aafly.token` | Créer des jetons |
| `aafly.speed`, `aafly.speed.<1-10>` | Changer sa vitesse. Le plafond est le plus haut `<n>` possédé. |
| `aafly.unlimited` | Vol illimité, sans décompte |
| `aafly.bypass.rules` | Ignore les règles de vol (et ne décompte pas si `rules.bypass-no-decrement`) |
| `aafly.group.<nom>` | Appartenance au groupe timed-fly `<nom>` |
| `aafly.admin` | Aide d'administration |
| `aafly.admin.time`, `aafly.admin.time.all` | Gestion du temps |
| `aafly.admin.toggle`, `aafly.admin.status` | Contrôle et état du vol d'un joueur |
| `aafly.admin.token` | Création de jetons d'administration |
| `aafly.admin.reload`, `aafly.admin.about` | Rechargement, informations |
| `wfly.infinite.fly`, `wfly.bypass.fly` | Alias de compatibilité wFly (si `compat.wfly-permissions: true`) |

> Les OP **n'ont pas** le vol illimité par défaut. Donnez explicitement `aafly.unlimited`, ou passez
> `flight.unlimited-for-op` à `true`.
>
> Valeurs par défaut (déclarées dans `paper-plugin.yml`) : toutes les permissions joueur et admin sont
> réservées aux OP ; accordez `aafly.fly`, `aafly.time`, etc. à vos joueurs via LuckPerms.
> `aafly.unlimited`, `aafly.bypass.rules` et les alias `wfly.*` valent `false`, même pour un OP.

## Placeholders (PlaceholderAPI)

| Placeholder | Valeur |
|---|---|
| `%aafly_remaining%` | Temps restant formaté (selon `format`) |
| `%aafly_remaining_seconds%` | Temps restant en secondes. Vaut `unlimited` si le vol est illimité. |
| `%aafly_remaining_minutes%` | Temps restant en minutes entières. Vaut `unlimited` si le vol est illimité. |
| `%aafly_remaining_hours%` | Temps restant en heures entières. Vaut `unlimited` si le vol est illimité. |
| `%aafly_remaining_days%` | Temps restant en jours entiers. Vaut `unlimited` si le vol est illimité. |
| `%aafly_enabled%` | Vol activé (`true`/`false`) |
| `%aafly_flying%` | En train de voler (`true`/`false`) |
| `%aafly_unlimited%` | Vol illimité (`true`/`false`) |
| `%aafly_speed%` | Niveau de vitesse (1 à 10) |
| `%aafly_cooldown%` | Recharge timed-fly restante (formatée) |

Si `compat.wfly-placeholders: true`, l'expansion de compatibilité `wfly` fournit aussi :
`%wfly_fly_remaining%`, `%wfly_fly_activate%` et `%wfly_fly_remaining_<seconds|minutes|hours|days>%`.

## Règles de vol

Les règles de `fly-rules` décident si le vol est **autorisé** (`ALLOW`) ou **interdit** (`DENY`) :

- Les règles sont triées par `priority` **croissante**.
- La **première** règle dont **toutes** les conditions correspondent décide.
- Si aucune règle ne correspond, le vol est autorisé.

Quand une règle interdit le vol, celui-ci est coupé avec la cause `RULE` et ses `actions` sont exécutées. Il est
réactivé automatiquement quand la règle ne s'applique plus, si `flight.auto-reactivate-after-rule` est activé.

Les `decrement-rules` utilisent les mêmes conditions, sans `result` : si l'une correspond, **le temps n'est pas
décompté**.

### Types de conditions

| Type | Paramètres | Correspond si... |
|---|---|---|
| `string_equals` | `input`, `value` | `input` est égal à `value` |
| `string_equals_ignorecase` | `input`, `value` | idem, sans tenir compte de la casse |
| `string_contains` | `input`, `value` | `input` contient `value` |
| `string_contains_ignorecase` | `input`, `value` | idem, sans tenir compte de la casse |
| `string_regex` | `input`, `pattern` | l'expression régulière correspond. Elle est compilée au chargement, et l'entrée est tronquée à 256 caractères. |
| `number_equals` | `input`, `value` | égalité numérique |
| `number_greater` | `input`, `value` | `input > value` |
| `number_less` | `input`, `value` | `input < value` |
| `number_between` | `input`, `min`, `max` | `min ≤ input ≤ max` |
| `has_permission` | `permission` | le joueur a la permission |
| `world` | `worlds` (liste) | le joueur est dans l'un de ces mondes |
| `gamemode` | `gamemodes` (liste) | le joueur est dans l'un de ces modes de jeu |

Les placeholders PlaceholderAPI de `input` sont résolus, puis la valeur obtenue est **comparée**. Elle n'est
jamais exécutée. Une valeur non numérique fait échouer une condition `number_*` sans provoquer d'erreur. Une
règle invalide est ignorée et un avertissement explicite (nom de la règle, raison) est journalisé.

### Exemples

```yaml
fly-rules:
  # Les admins volent partout.
  admin_bypass:
    priority: 0
    conditions:
      perm: { type: has_permission, permission: "aafly.admin" }
    result: ALLOW

  # Pas de vol en combat (CombatLogX).
  no_combat:
    priority: 1
    conditions:
      c: { type: string_equals, input: "%combatlogx_in_combat%", value: "true" }
    result: DENY
    actions: ["[actionbar] <red>Combat en cours"]

  # Pas de vol dans le Nether ni dans l'End.
  no_nether:
    priority: 5
    conditions:
      w: { type: world, worlds: ["world_nether", "world_the_end"] }
    result: DENY
    actions: ["[message] <prefix><red>Le vol est interdit dans cette dimension."]

  # Vol autorisé uniquement sur son propre claim (Lands).
  own_land:
    priority: 10
    conditions:
      owner: { type: string_equals_ignorecase, input: "%lands_land_owner_name%", value: "%player_name%" }
    result: ALLOW

  # Pas de vol au-dessus de la couche 250.
  max_height:
    priority: 20
    conditions:
      y: { type: number_greater, input: "%player_y%", value: 250 }
    result: DENY

  default:
    priority: 999
    result: DENY      # tout ce qui n'est pas explicitement autorisé est interdit

decrement-rules:
  # Le temps ne se décompte pas au spawn.
  spawn_free:
    conditions:
      region: { type: string_contains, input: "%worldguard_region_name%", value: "spawn" }
```

### Joueurs proches (PvP)

Avec `pvp.enabled: true`, le vol de chaque joueur est coupé dès qu'un autre joueur se trouve à moins de `radius`
blocs (cause `NEARBY`). Un joueur proche est considéré comme **allié**, et donc ignoré, s'il a la même valeur
pour **tous** les `bypass-placeholders` :

```yaml
pvp:
  enabled: true
  radius: 8
  bypass-placeholders: ["%factionsuuid_faction_name%"]
```

## Jetons de vol

`/fly convert <temps>` retire `<temps>` du solde du joueur et lui donne un objet configurable (`token.*`).
Un **clic droit** avec cet objet crédite le temps.

- Chaque jeton porte un identifiant unique, une durée et une signature **HMAC-SHA256** calculée avec le secret
  local `plugins/AAFly/token.secret`. Un jeton modifié ou forgé est refusé.
- **Protection contre la duplication** : un jeton n'est encaissé qu'**une seule fois**, grâce à une mise à jour
  conditionnelle en base de données. Un jeton dupliqué par un bug du jeu n'est donc crédité qu'une fois.
- L'objet n'est consommé qu'**après** la validation en base. En cas d'échec, l'objet reste intact.
- Si l'inventaire est plein, le jeton est déposé aux pieds du joueur.
- `/aafly convert <joueur> <temps>` crée un jeton sans débiter le joueur (récompenses, boutique).

> Supprimer `token.secret` génère un nouveau secret au démarrage suivant et **invalide tous les jetons
> existants**. Faites-le si le secret a fuité.

## Mode timed-fly

Avec `timed-fly.enabled: true`, le temps de vol n'est plus acheté ni donné : il est **géré par groupe**.

1. Chaque joueur reçoit le `fly-time` de son groupe.
2. Quand son vol se coupe, par expiration ou par désactivation, une **recharge** de `cooldown` secondes démarre.
   Si `reset-after-disable: true`, le temps restant est aussi remis à zéro.
3. Une fois la recharge écoulée, le temps est rechargé à `fly-time`. La recharge n'a jamais lieu pendant un vol.

Le groupe d'un joueur est déterminé en parcourant `groups` **dans l'ordre**. Le premier groupe qui correspond
gagne :

- le **groupe primaire LuckPerms** du joueur porte ce nom (si LuckPerms est installé) ;
- ou le joueur a la permission `aafly.group.<nom>` ;
- ou le groupe s'appelle `default`, qui sert de repli à tous les joueurs.

Placez donc les groupes les plus avantageux en premier, et `default` en dernier. Les noms de groupes doivent
respecter `^[a-z0-9_-]{1,32}$`.

Quand le mode est actif, les commandes suivantes sont **refusées** : `/aafly add`, `/aafly remove`,
`/aafly set`, `/aafly addall`, `/aafly removeall`, ainsi que les jetons. Le message `timed-fly-locked` est alors
affiché.

L'option `decrement-cooldown-offline` règle le comportement de la recharge hors-ligne :

- `true` : la recharge continue de s'écouler pendant que le joueur est hors-ligne ;
- `false` : la recharge est gelée pendant l'absence.

## API développeurs

```java
FlightApi api = Bukkit.getServicesManager().load(FlightApi.class);
if (api != null) {
    api.addTime(player.getUniqueId(), 600).thenAccept(result -> {
        if (result.success()) {
            player.sendMessage("+10 minutes de vol !");
        }
    });
}
```

- **Méthodes** : `remainingSeconds`, `isFlightEnabled`, `hasUnlimited`, `addTime`, `removeTime`, `setTime`,
  `enableFlight`, `disableFlight`, `registerCondition` (nouveau type de condition de règle) et `formatTime`.
- **Événements** :
  - `FlightToggleEvent` est annulable. L'annulation est ignorée pour une expiration.
  - `FlightTimeChangeEvent`.
  - `FlightExpireEvent`.
- **Threads** : toutes les méthodes s'appellent depuis le main thread, et les futures se terminent sur le
  main thread.

Déclarez AAFly en dépendance douce dans votre `paper-plugin.yml` ou `plugin.yml`.

## Migration depuis wFly

AAFly n'importe pas automatiquement les données de wFly. Le stockage est différent, et cela reste un choix de
sécurité : aucun fichier tiers n'est lu. Utilisez `/aafly set <joueur> <temps>` ou l'API pour les soldes à reprendre.

| wFly | AAFly | Remarque |
|---|---|---|
| `/fly` | `/fly` | Identique. Les alias se règlent dans `commands.fly-aliases`. |
| `/wfly fly <joueur>`, `/wfly activate\|deactivate <joueur>` | `/aafly toggle\|activate\|deactivate <joueur>` | |
| `/wfly add\|addtime <joueur> <temps> [unités]` | `/aafly add <joueur> <temps> [-s]` | Les unités sont incluses dans la durée : `1h30m` |
| `/wfly remove\|removetime <joueur> <temps>` | `/aafly remove <joueur> <temps> [-s]` | |
| `/wfly reset <joueur>` | `/aafly reset <joueur> [-s]` | |
| `/wfly addall\|removeall <n>` | `/aafly addall\|removeall <temps> [-s]` | Joueurs en ligne |
| `/wfly give\|giveall <joueur> <temps>` | `/fly give <joueur> <temps>` (depuis son solde), `/aafly add` (administration) | |
| `/wfly convert <joueur> <temps> <unités>` | `/aafly convert <joueur> <temps>` ou `/fly convert <temps>` | Jetons signés et protégés contre la duplication |
| `/wfly reload` | `/aafly reload` | |
| `/flyspeed` | `/flyspeed <1-10>` | Plafond par `aafly.speed.<n>` |
| `wfly.bypass.fly` | `aafly.bypass.rules` | Alias actif si `compat.wfly-permissions` |
| `wfly.infinite.fly` | `aafly.unlimited` | Alias actif si `compat.wfly-permissions` |
| OP = illimité | `flight.unlimited-for-op: false` par défaut | À activer explicitement |
| `%wfly_fly_remaining%` | `%aafly_remaining%` | `%wfly_...%` reste disponible si `compat.wfly-placeholders` |
| `%wfly_fly_remaining_<unité>%` | `%aafly_remaining_<unité>%` | |
| `%wfly_fly_activate%` | `%aafly_enabled%` | |
| `PLAYER_FLYING_MODE` / mode permanent | `decrement.mode: FLYING_MODE` / `FLY_MODE` | |
| `fly-decrement-disabled-by-static` + `delay` | `decrement.disable-when-static` + `static-delay-seconds` | |
| Décompte hors-ligne | `decrement.offline` | |
| `decrementation-disable-by-condition` | `decrement-rules` | |
| `commands-time-remaining` | `commands-time-remaining` (messages.yml) | Format d'action `[type] contenu` |
| `show-flytime-actionbar`, `hide-if-unlimited` | `actionbar.enabled`, `actionbar.hide-if-unlimited` | |
| `auto-reactivate-fly-after-conditions-disable` | `flight.auto-reactivate-after-rule` | |
| `tp-on-floor-when-fly-disabled` | `flight.tp-on-floor-when-disabled` | |
| Conditions (placeholder + opérateur + valeur) | `fly-rules` (types `string_*`, `number_*`...) | Priorités explicites |
| Partie PvP / factions proches | `pvp.*` + `bypass-placeholders` | |
| SQLite / MySQL | SQLite / MySQL | Redis n'est pas pris en charge |
| Vérificateur de mises à jour | — | Aucun accès réseau, par choix de sécurité |

## Compilation

Prérequis : JDK 25. Le toolchain Gradle produit un bytecode compatible avec Java 21.

```bash
./gradlew build          # compilation + tests ; le jar est dans build/libs/AAFly-<version>.jar
```

La CI GitHub Actions (`.github/workflows/build.yml`) compile et teste chaque push et chaque pull request, puis
publie le jar en artefact.

## Sécurité

Les mesures de sécurité et la procédure de signalement sont décrites dans [SECURITY.md](SECURITY.md). Pour
signaler une vulnérabilité, **n'ouvrez pas d'issue publique**.

## Licence et crédits

- Licence **MIT** : voir [LICENSE](LICENSE). Copyright (c) 2026 AzurHOSTS — Hugo CORBIN.
- Conception et développement : **[AzurHOSTS](https://azurhosts.com)**, hébergement VPS, serveurs de jeux et
  infrastructure.
- Fonctionnalités inspirées de wFly (Wayvi2), réimplémentées de zéro (*clean-room*).

**Developped by AzurHOSTS.com and powered by AzurWare.fr.**
