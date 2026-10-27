package com.azurhosts.aafly.hook;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jspecify.annotations.Nullable;

/**
 * Accroche optionnelle vers LuckPerms : fournit le groupe primaire d'un joueur.
 *
 * <p>LuckPerms est une dépendance douce : la seule référence à son API est isolée dans
 * {@link Delegate}, chargée uniquement lorsque LuckPerms est présent et activé. Si LuckPerms est
 * absent, désactivé ou en erreur, {@link #primaryGroup(Player)} renvoie vide (repli sur les
 * permissions {@code aafly.group.<nom>}). Main thread uniquement.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class LuckPermsHook {

    /** Nom du plugin LuckPerms. */
    public static final String PLUGIN_NAME = "LuckPerms";

    private final Logger logger;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    /**
     * @param logger logger du plugin (une erreur LuckPerms n'est signalée qu'une fois)
     */
    public LuckPermsHook(Logger logger) {
        this.logger = logger;
    }

    /**
     * @return {@code true} si LuckPerms est installé et activé
     */
    public static boolean isPresent() {
        Plugin lp = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        return lp != null && lp.isEnabled();
    }

    /**
     * Groupe primaire LuckPerms du joueur, en minuscules.
     *
     * @param player joueur en ligne
     * @return groupe primaire, ou vide si LuckPerms est indisponible ou l'utilisateur non chargé
     */
    public Optional<String> primaryGroup(Player player) {
        if (!isPresent()) {
            return Optional.empty();
        }
        try {
            String group = Delegate.primaryGroup(player);
            return group == null || group.isEmpty()
                    ? Optional.empty()
                    : Optional.of(group.toLowerCase(Locale.ROOT));
        } catch (RuntimeException | LinkageError e) {
            if (failureLogged.compareAndSet(false, true)) {
                logger.log(Level.WARNING,
                        "LuckPerms a levé une erreur : repli sur les permissions aafly.group.<nom>.", e);
            }
            return Optional.empty();
        }
    }

    /** Seule classe qui référence l'API LuckPerms. */
    private static final class Delegate {
        private Delegate() {
        }

        static @Nullable String primaryGroup(Player player) {
            RegisteredServiceProvider<LuckPerms> reg =
                    Bukkit.getServicesManager().getRegistration(LuckPerms.class);
            if (reg == null) {
                return null;
            }
            // getUser lève IllegalStateException si l'utilisateur n'est pas (encore) chargé.
            User user;
            try {
                user = reg.getProvider().getPlayerAdapter(Player.class).getUser(player);
            } catch (IllegalStateException notLoaded) {
                return null;
            }
            return user.getPrimaryGroup();
        }
    }
}
