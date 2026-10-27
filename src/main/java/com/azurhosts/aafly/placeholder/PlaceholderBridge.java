package com.azurhosts.aafly.placeholder;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Pont vers PlaceholderAPI : résout {@code %placeholders%} si PAPI est présent et activé, sinon renvoie
 * le texte tel quel. PAPI est détecté au runtime ; la seule référence à ses classes est isolée dans
 * {@link PapiDelegate}, qui n'est chargée que lorsque PAPI est disponible (aucune dépendance dure).
 *
 * <p>À appeler depuis le main thread. Le résultat est une donnée : il ne doit jamais être exécuté
 * ni injecté dans du MiniMessage non échappé.
 */
public final class PlaceholderBridge {

    private final @Nullable Logger logger;
    private final boolean detectable;
    private volatile boolean available;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    /**
     * Crée le pont et détecte PlaceholderAPI (nécessite un serveur Bukkit démarré).
     *
     * @param logger logger du plugin (pour signaler une erreur de PAPI une seule fois)
     */
    public PlaceholderBridge(Logger logger) {
        this.logger = logger;
        this.detectable = true;
        refresh();
    }

    private PlaceholderBridge() {
        this.logger = null;
        this.detectable = false;
        this.available = false;
    }

    /**
     * @return un pont inactif qui ne touche jamais à Bukkit (tests, repli)
     */
    public static PlaceholderBridge disabled() {
        return new PlaceholderBridge();
    }

    /**
     * Re-détecte PlaceholderAPI (à appeler au reload ou sur PluginEnable/DisableEvent de PAPI).
     */
    public void refresh() {
        if (!detectable) {
            return;
        }
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        available = papi != null && papi.isEnabled();
    }

    /**
     * @return {@code true} si PlaceholderAPI est présent et activé
     */
    public boolean available() {
        return available;
    }

    /**
     * Résout les placeholders du texte pour un joueur.
     *
     * @param player joueur (peut être {@code null} : placeholders globaux)
     * @param text   texte brut ({@code null} → chaîne vide)
     * @return texte résolu, ou le texte d'origine si PAPI est absent ou en erreur
     */
    public String apply(@Nullable OfflinePlayer player, @Nullable String text) {
        if (text == null) {
            return "";
        }
        if (!available || text.indexOf('%') < 0) {
            return text;
        }
        try {
            String out = PapiDelegate.apply(player, text);
            return out == null ? text : out;
        } catch (RuntimeException | LinkageError e) {
            if (logger != null && failureLogged.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "PlaceholderAPI a levé une erreur : placeholders ignorés.", e);
            }
            return text;
        }
    }

    /** Seule classe qui référence l'API de PlaceholderAPI. */
    private static final class PapiDelegate {
        private PapiDelegate() {
        }

        static String apply(@Nullable OfflinePlayer player, String text) {
            return PlaceholderAPI.setPlaceholders(player, text);
        }
    }
}
