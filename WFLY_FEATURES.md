# Relevé des fonctionnalités de wFly (référence, pas de code copié)

Source : changelog SpigotMC #118465 (56 updates, 07/2024 → 09/2026), doc https://wayfly-documentation.gitbook.io/wayfly-wiki
Versions MC : 1.8 → 1.21+/26.x. Auteur Wayvi2. Dépend de wConfigAPI (GPL-3), PlaceholderAPI.

## Commandes
- /fly (toggle), alias configurable (`command.alias` liste), /fly help
- /wfly fly <player> ; /wfly activate|deactivate <player>
- /wfly add|addtime <player> <temps> [unités] ; remove|removetime ; reset
- /wfly addall <n> ; /wfly removeall <n> (fonctionne sur proxy Bungee/Velocity)
- /wfly give|giveall <player> <temps> (tokens), flag `-s` silencieux sur give/giveall/removeall/removetime
- /wfly convert <player> <temps> <unités> → donne un item « fly token » (temps de vol dans une boîte, custommodeldata)
- /wfly reload (config + conditions sans restart)
- /wfly help
- /flyspeed (vitesse décimale autorisée)

## Permissions
- wfly.bypass.fly (ignore les conditions), permission vol infini/unlimited (ex. wfly.infinite), OP = illimité

## Temps de vol
- Temps par joueur, stocké ; unités seconds/minutes/hours/days ; format configurable (désactiver une unité ne « perd » pas le temps)
- Méthode de décrément : PLAYER_FLYING_MODE (décrémente seulement quand il vole) vs toujours ; option `fly-decrement-disabled-by-static` + `delay: 3` (pas de décrément si immobile)
- Décrément hors-ligne optionnel (offline countdown)
- Désactivation du décrément quand une condition bloque (decrementation-disable-by-condition)
- commands-time-remaining : commandes exécutées à certains seuils de temps restant (ex. message à 60s, 10s)
- Action bar avec temps restant (`show-flytime-actionbar`, `hide-if-unlimited`)
- Titre/messages à l'activation ; message NOT_FLY_HERE quand conditions bloquent

## Conditions (« fly rules »)
- Système de règles : placeholder (PAPI) + opérateur + valeur → autorise / interdit le vol
- Types : equals, not_equals, >, <, string_contains, string_contains_ignorecase…
- Commandes exécutées quand une condition non autorisée matche
- auto-reactivate-fly-after-conditions-disable : réactive le vol quand le joueur rentre dans une zone autorisée
- Partie PVP / joueurs proches (faction) : coupe le vol si ennemi proche
- Logger pour conditions invalides

## Sécurité / comportement
- tp-on-floor-when-fly-disabled : téléporte au sol (safe location, garde l'orientation, pas de dégâts de chute) quand le vol se coupe (0s, condition, changement de monde)
- Gestion changement de monde, gamemode (creative→survival, spectator), reconnexion (vol réactivé si actif avant déconnexion)
- Sauvegarde async à la déconnexion, upsert

## Stockage
- SQLite (défaut), MySQL (sync multi-serveurs : temps + état), Redis (bêta)
- Migration depuis TempFlight

## Intégrations
- PlaceholderAPI : %wfly_fly_remaining%, %wfly_fly_remaining_<seconds|minutes|hours|days>% (« unlimited » si illimité), %wfly_fly_activate%
- ClueScrolls (quête), couleurs hex Spigot, messages configurables (FR), updater (notification de version)
