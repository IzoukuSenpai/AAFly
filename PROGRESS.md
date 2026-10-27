# AAFly — journal de progression (temps réel)

Dépôt cible : https://github.com/IzoukuSenpai/AAFly (vide au départ)
Référence fonctionnelle : wFly (Wayvi2, SpigotMC #118465, v1.0.5.2 du 21/09/2026).
Le dépôt source Wayvi2/wFLyV2 annoncé sur SpigotMC n'existe plus (404) → reproduction clean-room, à notre manière.

## État
- [x] 2026-10-27 : recherche licence/source. Pas de source dispo → clean-room.
- [x] 2026-10-27 : relevé des fonctionnalités → WFLY_FEATURES.md (changelog + doc GitBook complète)
- [ ] Conception architecture (SPEC.md)
- [ ] Squelette Gradle + build
- [ ] Implémentation (workflow multi-agents « ultracode »)
- [ ] Compilation OK
- [ ] Push GitHub

## Notes
- [x] 2026-10-27 : conception -> docs/SPEC.md (architecture complete, securite, config, commandes)
- [~] 2026-10-27 : workflow multi-agents lance (scaffold -> 6 implementeurs -> integration -> revue -> fix). Les agents ajoutent leurs lignes ci-dessous.
- [ ] 2026-10-27 : squelette Gradle + contrats inter-packages (agent skeleton) — EN COURS : lecture SPEC, inspection AAEngine
- [~] 2026-10-27 : workflow relance avec tous les agents sur Opus 5.5 (le scaffold partiel precedent est repris/ecrase).
- [x] 2026-10-27 : squelette Gradle, ressources, config/time/token implementes, contrats inter-packages, build vert
- [x] 2026-10-27 : REA de Dylan integre (docs/REA.md) + SPEC-phase2.md (corrections REA, migration wFly/TempFly, compat AAItems). Applique apres la fin du workflow 1.
- [~] 2026-10-27 : AAItems : workflow lance dans le worktree Ruza Drive\AAItems-aafly (branche feat/integration-aafly depuis main) : fournisseur de vol externe. Module aaitems-integration-aafly ensuite, une fois le jar API AAFly produit.
- [x] timed-fly, résolveur groupes, hooks LuckPerms/PAPI, README, LICENSE, SECURITY, CI
- [x] Placeholders PAPI aafly/wfly, service jetons signés et TokenListener implémentés
- [x] 2026-10-27 : commandes /fly /aafly /flyspeed /flytime, CommandUtil, enregistrement Brigadier Paper
- [x] storage : dépôts SQLite/MySQL, migrateur, StorageManager cache/flush, tests SQLite
- [x] 2026-10-27 : core (service, ticker, landing, speed), listeners, API publique implémentés
- [x] 2026-10-27 : rules/ et action/ implementes (moteur, conditions, actions, seuils, tests)
- [x] 2026-10-27 : integration AAFlyPlugin (wire/reload/arret), corrections, permissions, build vert
- [x] Revue perf lecture seule de src : ticker, PAPI, stockage, fuites
- [x] Revue sécurité lecture seule de src : landing, tokens multi-serveur, spam
- [x] Revue conformité SPEC de src/ : écarts commandes, règles, tokens, PVP
- [x] Revue correctness lecture seule de src : ticker, timed-fly, reconnexion, stockage
- [x] Vérification finding profils suspendus après reload : confirmé, non réfuté
- [x] Vérification finding peek O(n) OfflineProfileCache : non réfuté
- [x] Vérification finding flush hors-ligne : confirmé, impact borné à 1/s
- [x] 2026-10-27 : AAItems : fournisseur de vol externe fait + revu (correctif double proprietaire du vol). Worktree deplace dans Minecraft\AAItems-aafly suite au rangement. Modules api/core/platform verts ; plugin non verifiable (jars JobsReborn absents).
- [x] Correctifs lot 1/5 : SafeLanding, timed-fly, règles, jetons, respawn, cache MySQL
- [x] Lot 2/5 : cooldown convert, suspension NEARBY, perfs action bar/PAPI/sauvegarde
- [x] Lot 3/5 : anti-spam /fly, monde, reload, PVP bypass, SPEC
- [x] Lot 4/5 : réactivation silencieuse, GroupResolver, %remaining%, DecrementPolicy, bypass
- [x] Lot 5/5 : loadOnline remove O(1), filtre NPC détecteur, règle alliance unifiée
- [x] Build final vert : C:/Users/izouk/Ruza Drive/Synchro inter PC/AAFly/build/libs/AAFly-1.0.0.jar
- [x] Vérification finding profils suspendus après reload : confirmé, non réfuté
