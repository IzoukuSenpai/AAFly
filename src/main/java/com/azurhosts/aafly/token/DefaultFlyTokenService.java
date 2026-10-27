package com.azurhosts.aafly.token;

import com.azurhosts.aafly.AAFlyPlugin;
import com.azurhosts.aafly.api.events.FlightTimeChangeEvent;
import com.azurhosts.aafly.config.Lang;
import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.core.DisableCause;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.core.FlightService;
import com.azurhosts.aafly.storage.StorageManager;
import com.azurhosts.aafly.storage.TokenRecord;
import com.azurhosts.aafly.time.TimeMath;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.persistence.PersistentDataContainerView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * Implémentation des jetons de vol (SPEC §7).
 *
 * <h2>Création</h2>
 * Conversion par un joueur : le solde est vérifié et débité en mémoire sur le main thread, puis le profil débité
 * et la ligne du jeton (nonce {@link UUID#randomUUID()} issu de {@code SecureRandom}) sont écrits <b>dans une
 * même transaction</b> (async) ; l'item n'est remis qu'après le commit. Le débit est donc durable avant que
 * l'item n'existe (pas de duplication par crash ou changement de serveur MySQL). Si l'écriture échoue ou si le
 * joueur s'est déconnecté entre-temps, le débit est remboursé et la ligne éventuelle reste orpheline
 * (inexploitable sans l'item signé). Création admin : insertion du jeton puis remise, sans débit.
 *
 * <h2>Encaissement</h2>
 * Signature HMAC vérifiée en temps constant → {@code UPDATE} conditionnel en base (async, anti-duplication :
 * un seul encaissement possible par jeton) → crédit sur le main thread → consommation d'<b>une</b> unité de
 * l'item portant ce {@code token_id}. En cas d'échec, l'item reste intact. Un seul encaissement et une seule
 * création en cours par joueur.
 *
 * <p>Toutes les méthodes publiques s'appellent depuis le main thread ; les futures se complètent sur le
 * main thread. Les messages de résultat sont envoyés par l'appelant (commande, {@link TokenListener}).
 */
public final class DefaultFlyTokenService implements FlyTokenService {

    /** Délai minimal entre deux avertissements de jeton falsifié pour un même joueur. */
    private static final long FORGERY_WARN_INTERVAL_MILLIS = 60_000L;
    /** Longueur canonique d'un UUID textuel. */
    private static final int UUID_LENGTH = 36;

    private final AAFlyPlugin plugin;
    private final TokenSigner signer;
    private final NamespacedKey keyTokenId;
    private final NamespacedKey keySeconds;
    private final NamespacedKey keySignature;

    private final Set<UUID> redeeming = ConcurrentHashMap.newKeySet();
    private final Set<UUID> creating = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastForgeryWarn = new ConcurrentHashMap<>();
    private volatile @Nullable String warnedMaterial;

    /**
     * @param plugin plugin (accès paresseux aux services : réglages, stockage, vol, timed-fly, messages)
     * @param signer signataire HMAC chargé depuis {@code token.secret}
     */
    public DefaultFlyTokenService(AAFlyPlugin plugin, TokenSigner signer) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.keyTokenId = new NamespacedKey(PDC_NAMESPACE, KEY_TOKEN_ID);
        this.keySeconds = new NamespacedKey(PDC_NAMESPACE, KEY_SECONDS);
        this.keySignature = new NamespacedKey(PDC_NAMESPACE, KEY_SIGNATURE);
    }

    /** Contenu PDC lu sur un item ({@code valid == false} si les types/formats sont incorrects). */
    private record TokenData(@Nullable UUID tokenId, long seconds, @Nullable String signature, boolean valid) {
        static final TokenData MALFORMED = new TokenData(null, 0L, null, false);
    }

    // ------------------------------------------------------------------ création

    @Override
    public CompletableFuture<TokenResult> createForPlayer(Player player, long seconds) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        Settings s = plugin.settings();
        if (!s.token().enabled()) {
            return done(TokenResult.DISABLED);
        }
        if (plugin.timedFly().enabled()) {
            return done(TokenResult.TIMED_FLY_LOCKED);
        }
        if (seconds <= 0L || seconds > s.limits().maxSeconds()) {
            return done(TokenResult.INVALID_AMOUNT);
        }
        FlightService flight = plugin.flightService();
        if (flight.hasUnlimited(player)) {
            return done(TokenResult.UNLIMITED);
        }
        UUID uuid = player.getUniqueId();
        FlightProfile profile = plugin.storage().cached(uuid);
        if (profile == null) {
            return done(TokenResult.STORAGE_ERROR);
        }
        if (profile.remainingSeconds() < seconds) {
            return done(TokenResult.INSUFFICIENT_TIME);
        }
        if (!creating.add(uuid)) {
            // Une création est déjà en cours pour ce joueur (anti-spam / anti-course).
            return done(TokenResult.STORAGE_ERROR);
        }
        TokenRecord record = new TokenRecord(UUID.randomUUID(), seconds, uuid, Instant.now().getEpochSecond());
        CompletableFuture<TokenResult> future;
        try {
            future = debitPersistThenGive(player, profile, record);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Erreur lors de la création du jeton " + record.tokenId(), e);
            future = done(TokenResult.STORAGE_ERROR);
        }
        return future.whenComplete((r, err) -> creating.remove(uuid));
    }

    @Override
    public CompletableFuture<TokenResult> createAdmin(@Nullable CommandSender actor, Player target, long seconds) {
        requireMainThread();
        Objects.requireNonNull(target, "target");
        Settings s = plugin.settings();
        if (!s.token().enabled()) {
            return done(TokenResult.DISABLED);
        }
        if (plugin.timedFly().enabled()) {
            return done(TokenResult.TIMED_FLY_LOCKED);
        }
        if (seconds <= 0L || seconds > s.limits().maxSeconds()) {
            return done(TokenResult.INVALID_AMOUNT);
        }
        UUID createdBy = actor instanceof Player p ? p.getUniqueId() : null;
        UUID targetId = target.getUniqueId();
        TokenRecord record = new TokenRecord(UUID.randomUUID(), seconds, createdBy, Instant.now().getEpochSecond());
        String actorName = actor == null ? "API" : actor.getName();
        return insertThen(record, () -> {
            Player online = Bukkit.getPlayer(targetId);
            if (online == null || !online.isOnline()) {
                return TokenResult.STORAGE_ERROR;
            }
            ItemStack item = buildItem(record.tokenId(), record.seconds());
            TokenResult r = give(online, item);
            plugin.getLogger().info("Jeton de vol " + record.tokenId() + " (" + record.seconds() + " s) créé par "
                    + actorName + " pour " + online.getName() + " (" + targetId + ").");
            return r;
        });
    }

    /** Insère le jeton (thread I/O) puis exécute {@code onMain} sur le main thread. */
    private CompletableFuture<TokenResult> insertThen(TokenRecord record,
                                                      java.util.function.Supplier<TokenResult> onMain) {
        StorageManager storage = plugin.storage();
        Executor main = storage.mainThreadExecutor();
        CompletableFuture<Void> insert;
        try {
            insert = storage.repository().insertToken(record);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Insertion du jeton impossible", e);
            return done(TokenResult.STORAGE_ERROR);
        }
        return insert.handleAsync((ignored, err) -> {
            if (err != null) {
                plugin.getLogger().log(Level.WARNING, "Insertion du jeton " + record.tokenId() + " en base impossible",
                        unwrap(err));
                return TokenResult.STORAGE_ERROR;
            }
            try {
                return onMain.get();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur lors de la remise du jeton " + record.tokenId(), e);
                return TokenResult.STORAGE_ERROR;
            }
        }, main).exceptionally(err -> TokenResult.STORAGE_ERROR);
    }

    /**
     * Main thread : débite le profil en mémoire, puis (thread I/O) écrit <b>dans une même transaction</b> le
     * profil débité et la ligne du jeton, et seulement après le commit remet l'item sur le main thread.
     * Un item n'existe donc jamais tant que le débit n'est pas durable en base (crash, changement de
     * serveur MySQL). Tout échec avant la remise rembourse le débit.
     */
    private CompletableFuture<TokenResult> debitPersistThenGive(Player player, FlightProfile profile,
                                                                 TokenRecord record) {
        UUID uuid = player.getUniqueId();
        StorageManager storage = plugin.storage();
        long seconds = record.seconds();
        long old = profile.remainingSeconds();
        if (old < seconds) {
            return done(TokenResult.INSUFFICIENT_TIME);
        }
        // Item construit avant tout débit : une erreur ici ne coûte rien au joueur.
        ItemStack item = buildItem(record.tokenId(), seconds);

        FlightTimeChangeEvent event = new FlightTimeChangeEvent(uuid, player, player,
                FlightTimeChangeEvent.Reason.TOKEN, old, old - seconds);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return done(TokenResult.DISABLED);
        }
        // Un listener peut réduire le débit, jamais créditer via une conversion.
        long newValue = Math.max(0L, Math.min(old, event.getNewSeconds()));
        long debit = old - newValue;
        profile.setRemainingSeconds(newValue);
        storage.markDirty(profile);

        CompletableFuture<Void> save;
        try {
            save = storage.repository().saveWithToken(profile.snapshot(), record);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Création du jeton " + record.tokenId() + " impossible", e);
            refund(uuid, profile, debit);
            return done(TokenResult.STORAGE_ERROR);
        }
        return save.handleAsync((ignored, err) -> {
            if (err != null) {
                plugin.getLogger().log(Level.WARNING, "Débit et création du jeton " + record.tokenId()
                        + " en base impossibles : temps remboursé", unwrap(err));
                refund(uuid, profile, debit);
                return TokenResult.STORAGE_ERROR;
            }
            try {
                return deliver(uuid, profile, record, item, debit);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur lors de la remise du jeton " + record.tokenId()
                        + " : temps remboursé", e);
                refund(uuid, profile, debit);
                return TokenResult.STORAGE_ERROR;
            }
        }, storage.mainThreadExecutor()).exceptionally(err -> TokenResult.STORAGE_ERROR);
    }

    /** Main thread, après commit : remet l'item ; joueur déconnecté entre-temps → remboursement. */
    private TokenResult deliver(UUID uuid, FlightProfile profile, TokenRecord record, ItemStack item, long debit) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            // La ligne du jeton reste orpheline (inexploitable sans l'item signé).
            plugin.getLogger().warning("Jeton " + record.tokenId() + " non remis (" + uuid
                    + " déconnecté) : " + debit + " s remboursés.");
            refund(uuid, profile, debit);
            return TokenResult.STORAGE_ERROR;
        }
        TokenResult result = give(player, item);
        plugin.getLogger().info("Jeton de vol " + record.tokenId() + " (" + record.seconds() + " s) créé par "
                + player.getName() + " (" + uuid + ").");
        FlightService flight = plugin.flightService();
        FlightProfile current = plugin.storage().cached(uuid);
        if (current != null && current.remainingSeconds() == 0L && flight.isEnabled(player)
                && !flight.hasUnlimited(player)) {
            flight.disable(player, DisableCause.EXPIRED, false);
        }
        return result;
    }

    /** Main thread : recrédite un débit non suivi de la remise de l'item (saturé à {@code limits.max-seconds}). */
    private void refund(UUID uuid, FlightProfile debited, long amount) {
        if (amount <= 0L) {
            return;
        }
        StorageManager storage = plugin.storage();
        FlightProfile target = storage.cached(uuid);
        if (target == null) {
            target = debited;
        }
        target.setRemainingSeconds(TimeMath.add(target.remainingSeconds(), amount,
                plugin.settings().limits().maxSeconds()));
        storage.markDirty(target);
    }

    /** Donne l'item ; inventaire plein → déposé aux pieds du joueur. */
    private static TokenResult give(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (leftover.isEmpty()) {
            return TokenResult.SUCCESS;
        }
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        return TokenResult.SUCCESS_DROPPED;
    }

    /**
     * Construit l'item d'un jeton (matériau, nom, lore, custom-model-data configurables, PDC signé).
     *
     * @param tokenId identifiant
     * @param seconds montant
     * @return item (quantité 1)
     */
    ItemStack buildItem(UUID tokenId, long seconds) {
        Settings.TokenSection cfg = plugin.settings().token();
        Material material = Material.matchMaterial(cfg.material());
        if (material == null || material.isAir() || !material.isItem()) {
            if (!cfg.material().equals(warnedMaterial)) {
                warnedMaterial = cfg.material();
                plugin.getLogger().warning("token.material '" + cfg.material()
                        + "' n'est pas un item valide : FEATHER utilisé.");
            }
            material = Material.FEATHER;
        }
        ItemStack item = ItemStack.of(material);
        // Durée formatée = chaîne de configuration + nombres : sûre à parser en MiniMessage.
        String time = plugin.timeFormatter().format(seconds);
        Lang lang = plugin.lang();
        Component name = noItalic(lang.parse(cfg.name().replace("%remaining%", time)));
        List<Component> lore = new ArrayList<>(cfg.lore().size());
        for (String line : cfg.lore()) {
            lore.add(noItalic(lang.parse(line.replace("%remaining%", time))));
        }
        item.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
        });
        String signature = signer.sign(tokenId, seconds);
        item.editPersistentDataContainer(pdc -> {
            pdc.set(keyTokenId, PersistentDataType.STRING, tokenId.toString());
            pdc.set(keySeconds, PersistentDataType.LONG, seconds);
            pdc.set(keySignature, PersistentDataType.STRING, signature);
        });
        if (cfg.customModelData() > 0) {
            item.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                    CustomModelData.customModelData().addFloat(cfg.customModelData()).build());
        }
        return item;
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    // ------------------------------------------------------------------ encaissement

    @Override
    public CompletableFuture<RedeemResult> redeem(Player player, ItemStack item) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        Settings s = plugin.settings();
        if (!s.token().enabled()) {
            return done(RedeemResult.DISABLED);
        }
        TokenData data = read(item);
        if (data == null) {
            return done(RedeemResult.NOT_A_TOKEN);
        }
        UUID uuid = player.getUniqueId();
        // Vérification HMAC en temps constant (TokenSigner#verify utilise MessageDigest.isEqual).
        if (!data.valid() || data.tokenId() == null || data.signature() == null || data.seconds() <= 0L
                || !signer.verify(data.tokenId(), data.seconds(), data.signature())) {
            warnForgery(player);
            return done(RedeemResult.INVALID_SIGNATURE);
        }
        if (plugin.timedFly().enabled()) {
            return done(RedeemResult.TIMED_FLY_LOCKED);
        }
        StorageManager storage = plugin.storage();
        FlightProfile profile = storage.cached(uuid);
        if (profile == null) {
            return done(RedeemResult.STORAGE_ERROR);
        }
        long max = s.limits().maxSeconds();
        long seconds = data.seconds();
        long old = profile.remainingSeconds();
        if (seconds > max || old > max - seconds) {
            return done(RedeemResult.LIMIT_EXCEEDED);
        }
        if (!redeeming.add(uuid)) {
            // Un encaissement est déjà en cours pour ce joueur.
            return done(RedeemResult.STORAGE_ERROR);
        }
        try {
            FlightTimeChangeEvent event = new FlightTimeChangeEvent(uuid, player, player,
                    FlightTimeChangeEvent.Reason.TOKEN, old, old + seconds);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                redeeming.remove(uuid);
                return done(RedeemResult.DISABLED);
            }
            long credit = Math.max(0L, event.getNewSeconds() - old);
            UUID tokenId = data.tokenId();
            Executor main = storage.mainThreadExecutor();
            return storage.repository()
                    .redeemToken(tokenId, seconds, uuid, Instant.now().getEpochSecond())
                    .handleAsync((ok, err) -> finishRedeem(uuid, tokenId, seconds, credit, ok, err), main)
                    .exceptionally(err -> RedeemResult.STORAGE_ERROR)
                    .whenComplete((r, err) -> redeeming.remove(uuid));
        } catch (RuntimeException e) {
            redeeming.remove(uuid);
            plugin.getLogger().log(Level.WARNING, "Encaissement du jeton impossible", e);
            return done(RedeemResult.STORAGE_ERROR);
        }
    }

    /** Main thread : crédite après succès de l'UPDATE conditionnel, puis consomme une unité de l'item. */
    private RedeemResult finishRedeem(UUID uuid, UUID tokenId, long seconds, long credit,
                                      @Nullable Boolean ok, @Nullable Throwable err) {
        if (err != null) {
            plugin.getLogger().log(Level.WARNING, "Encaissement du jeton " + tokenId + " en base impossible",
                    unwrap(err));
            return RedeemResult.STORAGE_ERROR;
        }
        if (ok == null || !ok) {
            return RedeemResult.ALREADY_REDEEMED;
        }
        // Le jeton est désormais encaissé en base : le crédit est dû quoi qu'il arrive à l'item.
        StorageManager storage = plugin.storage();
        long max = plugin.settings().limits().maxSeconds();
        Player player = Bukkit.getPlayer(uuid);
        FlightProfile profile = storage.cached(uuid);
        String who = player != null ? player.getName() + " (" + uuid + ")" : uuid.toString();
        if (profile != null) {
            profile.setRemainingSeconds(TimeMath.add(profile.remainingSeconds(), credit, max));
            storage.markDirty(profile);
        } else {
            // Déconnecté pendant l'accès base : crédit sur le profil hors-ligne.
            storage.loadExisting(uuid).whenComplete((opt, loadErr) -> {
                if (loadErr == null && opt != null && opt.isPresent()) {
                    FlightProfile offline = opt.get();
                    offline.setRemainingSeconds(TimeMath.add(offline.remainingSeconds(), credit, max));
                    storage.markDirty(offline);
                } else {
                    plugin.getLogger().severe("Jeton " + tokenId + " encaissé mais crédit de " + credit
                            + " s impossible pour " + uuid + " : à créditer manuellement.");
                }
            });
        }
        if (player != null && player.isOnline()) {
            if (!consumeOne(player, tokenId)) {
                debug("Jeton " + tokenId + " encaissé mais item introuvable dans l'inventaire de " + who
                        + " (déplacé) : il est désormais inutilisable.");
            }
        }
        plugin.getLogger().info("Jeton de vol " + tokenId + " (" + seconds + " s) encaissé par " + who + ".");
        return RedeemResult.SUCCESS;
    }

    /** Retire une unité du premier item portant ce {@code token_id} (mains d'abord, puis inventaire). */
    private boolean consumeOne(Player player, UUID tokenId) {
        PlayerInventory inv = player.getInventory();
        ItemStack main = inv.getItemInMainHand();
        if (hasTokenId(main, tokenId)) {
            inv.setItemInMainHand(decremented(main));
            return true;
        }
        ItemStack off = inv.getItemInOffHand();
        if (hasTokenId(off, tokenId)) {
            inv.setItemInOffHand(decremented(off));
            return true;
        }
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (hasTokenId(contents[i], tokenId)) {
                inv.setItem(i, decremented(contents[i]));
                return true;
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (hasTokenId(cursor, tokenId)) {
            player.setItemOnCursor(decremented(cursor));
            return true;
        }
        return false;
    }

    private static @Nullable ItemStack decremented(ItemStack stack) {
        int amount = stack.getAmount();
        if (amount <= 1) {
            return null;
        }
        ItemStack copy = stack.clone();
        copy.setAmount(amount - 1);
        return copy;
    }

    private boolean hasTokenId(@Nullable ItemStack item, UUID tokenId) {
        TokenData d = read(item);
        return d != null && d.valid() && tokenId.equals(d.tokenId());
    }

    // ------------------------------------------------------------------ lecture

    @Override
    public boolean isToken(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return false;
        }
        PersistentDataContainerView pdc = item.getPersistentDataContainer();
        return pdc.has(keyTokenId) && pdc.has(keySeconds) && pdc.has(keySignature);
    }

    /**
     * Montant inscrit sur un jeton (non vérifié), pour l'affichage.
     *
     * @param item item quelconque
     * @return secondes, ou vide si ce n'est pas un jeton lisible
     */
    public OptionalLong tokenSeconds(@Nullable ItemStack item) {
        TokenData d = read(item);
        return d != null && d.valid() && d.seconds() > 0L ? OptionalLong.of(d.seconds()) : OptionalLong.empty();
    }

    /**
     * @param uuid joueur
     * @return {@code true} si un encaissement est en cours pour ce joueur (anti-spam du clic)
     */
    public boolean isRedeeming(UUID uuid) {
        return redeeming.contains(uuid);
    }

    /**
     * Oublie l'état anti-spam d'un joueur (déconnexion).
     *
     * @param uuid joueur
     */
    public void forget(UUID uuid) {
        lastForgeryWarn.remove(uuid);
    }

    /** Lit le PDC ; {@code null} si ce n'est pas un jeton, {@link TokenData#MALFORMED} si mal formé. */
    private @Nullable TokenData read(@Nullable ItemStack item) {
        if (!isToken(item)) {
            return null;
        }
        PersistentDataContainerView pdc = item.getPersistentDataContainer();
        String rawId;
        Long seconds;
        String sig;
        try {
            rawId = pdc.get(keyTokenId, PersistentDataType.STRING);
            seconds = pdc.get(keySeconds, PersistentDataType.LONG);
            sig = pdc.get(keySignature, PersistentDataType.STRING);
        } catch (IllegalArgumentException wrongType) {
            return TokenData.MALFORMED;
        }
        if (rawId == null || seconds == null || sig == null || rawId.length() != UUID_LENGTH
                || sig.length() != TokenSigner.SIGNATURE_HEX_LENGTH) {
            return TokenData.MALFORMED;
        }
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException malformed) {
            return TokenData.MALFORMED;
        }
        // Forme canonique exigée (la signature porte sur UUID#toString()).
        if (!id.toString().equals(rawId)) {
            return TokenData.MALFORMED;
        }
        return new TokenData(id, seconds, sig, true);
    }

    // ------------------------------------------------------------------ utilitaires

    private void warnForgery(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastForgeryWarn.get(player.getUniqueId());
        if (last == null || now - last >= FORGERY_WARN_INTERVAL_MILLIS) {
            lastForgeryWarn.put(player.getUniqueId(), now);
            plugin.getLogger().warning("Jeton de vol à signature invalide présenté par " + player.getName()
                    + " (" + player.getUniqueId() + ") : item falsifié ou secret régénéré.");
        }
    }

    private void debug(String message) {
        try {
            if (plugin.settings().debug()) {
                plugin.getLogger().info("[debug] " + message);
            }
        } catch (IllegalStateException ignored) {
            // configuration non chargée
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while ((cur instanceof java.util.concurrent.CompletionException
                || cur instanceof java.util.concurrent.ExecutionException) && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("FlyTokenService doit être appelé depuis le main thread.");
        }
    }

    private static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
