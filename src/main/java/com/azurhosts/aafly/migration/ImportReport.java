package com.azurhosts.aafly.migration;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Rapport d'une migration (SPEC-phase2 A9). {@code read = imported + ignored + alreadyPresent}.
 *
 * @param read           lignes lues dans la source
 * @param imported       lignes écrites (créations + mises à jour)
 * @param updated        dont mises à jour d'un profil existant
 * @param ignored        lignes invalides ou en double, ignorées
 * @param alreadyPresent profils existants laissés tels quels (stratégie {@code skip}, ou {@code max}/{@code replace}
 *                       sans changement)
 * @param strategy       stratégie de fusion
 * @param dryRun         essai à blanc (rien n'a été écrit)
 * @param changed        joueurs dont la ligne a été (ou serait, en essai à blanc) créée ou modifiée
 */
public record ImportReport(int read, int imported, int updated, int ignored, int alreadyPresent,
                           MergeStrategy strategy, boolean dryRun, Set<UUID> changed) {

    public ImportReport {
        Objects.requireNonNull(strategy, "strategy");
        changed = Set.copyOf(changed);
    }

    /**
     * @return résumé d'une ligne (sans donnée personnelle)
     */
    public String summary() {
        return (dryRun ? "[essai à blanc] " : "") + "lus=" + read + ", importés=" + imported
                + " (dont mis à jour=" + updated + "), ignorés=" + ignored + ", déjà présents=" + alreadyPresent
                + ", fusion=" + strategy.id();
    }
}
