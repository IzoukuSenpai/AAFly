package com.azurhosts.aafly.core;

import com.azurhosts.aafly.api.ThreatResolver;
import com.azurhosts.aafly.api.ThreatResolver.Verdict;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Résolveurs de menace tiers enregistrés via l'API (SPEC-phase2 A6, {@code AAFlyApi#registerThreatResolver}).
 *
 * <p>Combinaison (contrat de {@link ThreatResolver}) : au moins un {@link Verdict#THREAT} → menace ; sinon au
 * moins un {@link Verdict#NOT_THREAT} → pas de menace ; sinon {@link Verdict#ABSTAIN} (la règle intégrée
 * {@link BuiltinThreatResolver} décide). Un résolveur qui lève une exception ou renvoie {@code null} s'abstient ;
 * l'erreur est journalisée une fois par résolveur. Un résolveur dont les classes ne sont plus chargeables
 * ({@link LinkageError} : plugin déchargé) est retiré.
 *
 * <p>Le plugin propriétaire est déduit du chargeur de classes du résolveur ; ses résolveurs sont retirés à sa
 * désactivation ({@link #unregisterAll(Plugin)}, appelé par le listener de {@code PluginDisableEvent}).
 * Appels sur le main thread ; liste copy-on-write (enregistrements rares, itération sans verrou).
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class ThreatResolverRegistry {

    /** Nombre maximal de résolveurs (borne défensive contre un enregistrement en boucle). */
    static final int MAX_RESOLVERS = 32;

    /** Résolveur enregistré, avec son plugin propriétaire s'il est connu. */
    private static final class Entry {
        private final ThreatResolver resolver;
        private final @Nullable Plugin owner;
        private boolean errorLogged;

        Entry(ThreatResolver resolver, @Nullable Plugin owner) {
            this.resolver = resolver;
            this.owner = owner;
        }
    }

    private final Logger logger;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    /**
     * @param logger logger du plugin
     */
    public ThreatResolverRegistry(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Enregistre un résolveur (idempotent : un même objet n'est enregistré qu'une fois).
     *
     * @param resolver résolveur
     * @throws IllegalStateException si {@link #MAX_RESOLVERS} résolveurs sont déjà enregistrés
     */
    public void register(ThreatResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        for (Entry e : entries) {
            if (e.resolver == resolver) {
                return;
            }
        }
        if (entries.size() >= MAX_RESOLVERS) {
            throw new IllegalStateException("Trop de résolveurs de menace enregistrés (" + MAX_RESOLVERS + " max.)");
        }
        entries.add(new Entry(resolver, owningPlugin(resolver)));
    }

    /**
     * @param resolver résolveur à retirer
     * @return {@code true} s'il était enregistré
     */
    public boolean unregister(ThreatResolver resolver) {
        return entries.removeIf(e -> e.resolver == resolver);
    }

    /**
     * Retire les résolveurs fournis par un plugin (désactivation de ce plugin).
     *
     * @param plugin plugin désactivé
     * @return nombre de résolveurs retirés
     */
    public int unregisterAll(Plugin plugin) {
        int before = entries.size();
        entries.removeIf(e -> e.owner == plugin);
        return before - entries.size();
    }

    /**
     * @return nombre de résolveurs tiers enregistrés
     */
    public int size() {
        return entries.size();
    }

    /**
     * Avis combiné des résolveurs tiers sur un joueur proche.
     *
     * @param flyer  joueur en vol
     * @param nearby joueur proche
     * @return avis combiné ({@link Verdict#ABSTAIN} si aucun résolveur ou si tous s'abstiennent)
     */
    public Verdict resolve(Player flyer, Player nearby) {
        if (entries.isEmpty()) {
            return Verdict.ABSTAIN;
        }
        boolean notThreat = false;
        for (Entry e : entries) {
            Verdict v = safeResolve(e, flyer, nearby);
            if (v == Verdict.THREAT) {
                return Verdict.THREAT;
            }
            notThreat |= v == Verdict.NOT_THREAT;
        }
        return notThreat ? Verdict.NOT_THREAT : Verdict.ABSTAIN;
    }

    /**
     * Règle de combinaison pure (testable) : THREAT l'emporte, puis NOT_THREAT, sinon ABSTAIN ; {@code null}
     * vaut ABSTAIN.
     *
     * @param verdicts avis individuels
     * @return avis combiné
     */
    static Verdict combine(Collection<@Nullable Verdict> verdicts) {
        boolean notThreat = false;
        for (Verdict v : verdicts) {
            if (v == Verdict.THREAT) {
                return Verdict.THREAT;
            }
            notThreat |= v == Verdict.NOT_THREAT;
        }
        return notThreat ? Verdict.NOT_THREAT : Verdict.ABSTAIN;
    }

    private Verdict safeResolve(Entry e, Player flyer, Player nearby) {
        try {
            Verdict v = e.resolver.resolve(flyer, nearby);
            return v == null ? Verdict.ABSTAIN : v;
        } catch (LinkageError gone) {
            entries.remove(e);
            logger.log(Level.WARNING, "Résolveur de menace " + describe(e) + " retiré (classes indisponibles)", gone);
            return Verdict.ABSTAIN;
        } catch (RuntimeException failure) {
            if (!e.errorLogged) {
                e.errorLogged = true;
                logger.log(Level.WARNING, "Résolveur de menace " + describe(e)
                        + " en erreur : avis ignoré (les erreurs suivantes ne seront plus journalisées)", failure);
            }
            return Verdict.ABSTAIN;
        }
    }

    private static String describe(Entry e) {
        String owner = e.owner == null ? "?" : e.owner.getName();
        return e.resolver.getClass().getName() + " (" + owner + ")";
    }

    private static @Nullable Plugin owningPlugin(ThreatResolver resolver) {
        try {
            return JavaPlugin.getProvidingPlugin(resolver.getClass());
        } catch (IllegalArgumentException | IllegalStateException | ClassCastException notFromPlugin) {
            return null;
        }
    }
}
