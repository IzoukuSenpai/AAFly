# Politique de sécurité — AAFly

AAFly est développé et maintenu par **AzurHOSTS**. La sécurité passe avant tout le reste : nous préférons
refuser une fonctionnalité plutôt que d'exposer un serveur.

## Versions prises en charge

| Version | Correctifs de sécurité |
|---|---|
| 1.0.x | Oui |
| < 1.0 | Non |

Seule la dernière version mineure reçoit des correctifs. Mettez à jour avant de signaler un problème.

## Signaler une vulnérabilité

**N'ouvrez pas d'issue publique** pour une faille de sécurité.

1. Utilisez le signalement privé de GitHub : onglet **Security**, puis **Report a vulnerability**
   sur le dépôt.
2. Si ce n'est pas possible, contactez l'équipe AzurHOSTS par le support de
   [azurhosts.com](https://azurhosts.com) en précisant « AAFly - sécurité » dans l'objet.

Merci d'indiquer :

- la version d'AAFly, de Paper et de Java ;
- les plugins concernés (PlaceholderAPI, LuckPerms, etc.) ;
- les étapes de reproduction et l'impact estimé (duplication, élévation de privilèges, injection, déni de service, etc.) ;
- une preuve de concept si vous en avez une, **sur votre propre serveur de test uniquement**.

Ne joignez jamais de secrets réels : mots de passe MySQL, fichier `token.secret`, bases de données de production.

### Nos engagements

| Étape | Délai indicatif |
|---|---|
| Accusé de réception | 72 heures |
| Première évaluation (gravité, versions touchées) | 7 jours |
| Correctif pour une faille critique ou élevée | 30 jours |

Nous publions un avis GitHub (GHSA) avec le correctif. Avec votre accord, nous vous y créditons.
Nous ne poursuivons pas les chercheurs de bonne foi qui respectent cette politique : pas d'accès aux
données d'autrui, pas d'atteinte à des serveurs tiers, pas de divulgation avant la publication du correctif.

## Mesures de sécurité intégrées (SPEC §11)

1. **SQL**
   - Uniquement des `PreparedStatement` avec paramètres liés. Les noms de tables et de colonnes sont fixes : aucune valeur n'est concaténée dans une requête.
   - Pool de connexions HikariCP.
   - Le mot de passe MySQL n'est jamais écrit dans les logs : le `toString()` de la configuration le masque.
2. **Threads**
   - Aucun appel à l'API Bukkit hors du main thread.
   - Les entrées/sorties passent par un exécuteur dédié (threads `AAFly-IO`), et le résultat revient sur le main thread.
   - À l'arrêt, les données sont écrites de façon synchrone, avec un délai maximal de 10 secondes.
3. **Entrées utilisateur**
   - Pseudos validés par `^[A-Za-z0-9_]{1,16}$`.
   - Durées bornées par `limits.max-seconds` et vitesse bornée entre 0.1 et 1.0.
   - Les valeurs dynamiques ne sont jamais insérées dans une commande, ni dans du MiniMessage non échappé : elles passent par `Component.text`.
   - Le résultat de PlaceholderAPI est traité comme une donnée et n'est jamais exécuté.
   - Les expressions régulières des règles sont compilées au chargement, et l'entrée est tronquée à 256 caractères.
4. **Jetons de vol**
   - Signature HMAC-SHA256 avec un secret local de 32 octets (`plugins/AAFly/token.secret`). Le fichier est créé avec `SecureRandom`, en mode 600 sur les systèmes POSIX.
   - Le secret n'est jamais écrit dans les logs ni versionné (`.gitignore`).
   - La signature est vérifiée en temps constant (`MessageDigest.isEqual`).
   - Chaque jeton ne peut être encaissé qu'une fois, grâce à un nonce en base : `UPDATE ... WHERE token_id=? AND seconds=? AND redeemed_at IS NULL`, avec une seule ligne modifiée attendue.
   - L'objet n'est consommé qu'après la réussite de l'écriture en base.
5. **Permissions**
   - Vérifiées sur chaque sous-commande.
   - La console n'est acceptée que sur les commandes d'administration.
   - Les OP n'ont **pas** le vol illimité par défaut (`flight.unlimited-for-op: false`).
   - L'action `[player]`, qui fait exécuter une commande par le joueur, est désactivée par défaut (`actions.allow-player-commands: false`).
   - Les commandes console ne viennent que de la configuration.
6. **Réseau**
   - Aucun téléchargement à l'exécution hors des bibliothèques Maven déclarées et résolues par Paper.
   - Pas de vérificateur de mises à jour et pas de métriques (pas de bStats).
7. **Dépassements de capacité**
   - Tous les calculs de temps passent par `TimeMath`, qui sature à `max-seconds` sans jamais devenir négatif.
   - Le mode timed-fly ne recharge jamais pendant un vol actif.
8. **Journaux**
   - Aucune donnée personnelle au-delà du pseudo et de l'UUID.
   - Le niveau DEBUG s'active par `debug: true`.
9. **Échec sûr**
   - Une configuration invalide désactive proprement le plugin. Il ne reste jamais dans un état à moitié chargé.
   - Une règle invalide est ignorée avec un avertissement explicite, sans faire planter le plugin.

## Bonnes pratiques d'exploitation

- Ne partagez jamais `token.secret`. S'il fuit, arrêtez le serveur, supprimez le fichier (un nouveau sera
  généré) : tous les jetons existants deviendront invalides.
- Avec MySQL, utilisez un utilisateur dédié limité à la base `aafly`. Activez `use-ssl: true` si la base est distante.
- Ne donnez `aafly.admin.*` et `aafly.unlimited` qu'aux comptes de confiance.

---

Developped by AzurHOSTS.com and powered by AzurWare.fr.
