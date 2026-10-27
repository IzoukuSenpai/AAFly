package com.azurhosts.aafly.listener;

import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.core.SafeLanding;
import com.azurhosts.aafly.storage.StorageManager;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.jspecify.annotations.Nullable;

/**
 * Suivi des déplacements, <b>uniquement</b> sur changement de bloc (les rotations et micro-déplacements dans
 * le même bloc sont ignorés, SPEC-phase2 A6) :
 * <ul>
 *   <li>détection d'immobilité ({@code decrement.disable-when-static}) : instant du dernier changement de bloc ;</li>
 *   <li>dernière position sûre au sol pour l'atterrissage sécurisé (SPEC-phase2 A3, repli 1), si
 *       {@code flight.tp-on-floor-when-disabled} : {@link SafeLanding#observe} limite la vérification à une
 *       par seconde et par joueur.</li>
 * </ul>
 * Coût O(1) par événement (une recherche dans le cache des profils en ligne, aucune allocation), rien si
 * aucune des deux options n'est utile. Seuls les joueurs dont le profil AAFly est chargé sont suivis.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class MoveListener implements Listener {

    private final Supplier<Settings> settings;
    private final StorageManager storage;
    private final @Nullable SafeLanding landing;

    /**
     * Suivi de l'immobilité seulement (sans mémorisation de position sûre).
     *
     * @param settings réglages courants
     * @param storage  stockage (profils en ligne)
     */
    public MoveListener(Supplier<Settings> settings, StorageManager storage) {
        this(settings, storage, null);
    }

    /**
     * @param settings réglages courants
     * @param storage  stockage (profils en ligne)
     * @param landing  atterrissage sécurisé (mémorisation de la dernière position sûre), ou {@code null}
     */
    public MoveListener(Supplier<Settings> settings, StorageManager storage, @Nullable SafeLanding landing) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.landing = landing;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        Settings s = settings.get();
        boolean trackStatic = s.decrement().disableWhenStatic();
        boolean trackSafe = landing != null && s.flight().tpOnFloorWhenDisabled();
        if (!trackStatic && !trackSafe) {
            return;
        }
        Player player = event.getPlayer();
        FlightProfile profile = storage.cached(player.getUniqueId());
        if (profile == null) {
            return;
        }
        if (trackStatic) {
            profile.setLastMoveEpochMillis(System.currentTimeMillis());
        }
        if (trackSafe) {
            landing.observe(player);
        }
    }
}
