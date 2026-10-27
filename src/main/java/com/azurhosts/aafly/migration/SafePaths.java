package com.azurhosts.aafly.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Résolution des fichiers sources de migration (SPEC-phase2 A9) : chemins <b>fixes</b>, résolus sous le
 * dossier {@code plugins}, sans suivre aucun lien symbolique ni point de jonction.
 *
 * <p>Chaque composant sous {@code plugins} est inspecté sans suivre les liens
 * ({@link LinkOption#NOFOLLOW_LINKS}) : lien symbolique ou fichier spécial → refus. Le chemin réel final
 * doit en outre rester sous le chemin réel de {@code plugins} (défense en profondeur contre les
 * jonctions Windows, que Java ne signale pas toujours comme liens).
 */
public final class SafePaths {

    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.\\-]{0,63}$");

    private SafePaths() {
    }

    /**
     * @param pluginsDir dossier {@code plugins} du serveur
     * @param segments   composants fixes (ex. {@code "wFlyV2", "database.db"})
     * @return chemin du fichier régulier existant
     * @throws MigrationException si le fichier est absent, n'est pas un fichier régulier, ou si un composant
     *                            est un lien symbolique / sort du dossier {@code plugins}
     */
    public static Path resolveFile(Path pluginsDir, String... segments) {
        Path found = resolve(pluginsDir, segments);
        if (found == null) {
            throw new MigrationException("Fichier introuvable : plugins/" + String.join("/", segments));
        }
        return found;
    }

    /**
     * Variante tolérant l'absence du fichier.
     *
     * @param pluginsDir dossier {@code plugins}
     * @param segments   composants fixes
     * @return chemin, ou {@code null} si le fichier n'existe pas
     * @throws MigrationException si un composant est un lien symbolique ou sort du dossier {@code plugins}
     */
    public static @Nullable Path resolveOptionalFile(Path pluginsDir, String... segments) {
        return resolve(pluginsDir, segments);
    }

    private static @Nullable Path resolve(Path pluginsDir, String... segments) {
        Objects.requireNonNull(pluginsDir, "pluginsDir");
        if (segments.length == 0) {
            throw new IllegalArgumentException("aucun composant");
        }
        String display = "plugins/" + String.join("/", segments);
        Path base = pluginsDir.toAbsolutePath().normalize();
        Path cur = base;
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (seg == null || !SEGMENT.matcher(seg).matches() || seg.equals("..") || seg.equals(".")) {
                throw new IllegalArgumentException("composant de chemin invalide");
            }
            cur = cur.resolve(seg);
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(cur, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                return null; // absent : resolveFile lève un message clair, resolveOptionalFile renvoie null
            } catch (IOException e) {
                throw new MigrationException("Lecture impossible : " + display + " (" + e.getClass().getSimpleName() + ")", e);
            }
            if (attrs.isSymbolicLink() || attrs.isOther()) {
                throw new MigrationException("Refusé : " + display + " passe par un lien symbolique ou un fichier"
                        + " spécial (les liens ne sont jamais suivis).");
            }
            boolean last = i == segments.length - 1;
            if (!last && !attrs.isDirectory()) {
                return null;
            }
            if (last && !attrs.isRegularFile()) {
                throw new MigrationException("Refusé : " + display + " n'est pas un fichier ordinaire.");
            }
        }
        try {
            Path realBase = base.toRealPath();
            Path real = cur.toRealPath();
            if (!real.startsWith(realBase)) {
                throw new MigrationException("Refusé : " + display + " sort du dossier plugins.");
            }
        } catch (IOException e) {
            throw new MigrationException("Lecture impossible : " + display + " (" + e.getClass().getSimpleName() + ")", e);
        }
        return cur;
    }
}
