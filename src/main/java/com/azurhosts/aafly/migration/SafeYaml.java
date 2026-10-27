package com.azurhosts.aafly.migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Lecture YAML <b>sûre</b> des fichiers d'autres plugins (SPEC-phase2 A9) : {@link SafeConstructor} de
 * SnakeYAML (uniquement maps, listes, chaînes, nombres, booléens, dates — aucune instanciation de classe,
 * les balises {@code !!java...} sont refusées), sans {@code ConfigurationSerializable} de Bukkit.
 * Limites : taille du fichier, alias, profondeur, clés récursives interdites ; fichier ouvert sans suivre
 * les liens.
 */
public final class SafeYaml {

    /** Taille maximale d'un fichier YAML lu (64 Mio). */
    public static final long MAX_BYTES = 64L * 1024L * 1024L;

    private SafeYaml() {
    }

    /**
     * @param file fichier (déjà résolu par {@link SafePaths})
     * @return racine du document (map), ou {@code null} si le document est vide ou n'est pas une map
     * @throws MigrationException fichier trop gros, illisible ou YAML invalide / non sûr
     */
    public static @Nullable Map<?, ?> loadMap(Path file) {
        try {
            long size = Files.size(file);
            if (size > MAX_BYTES) {
                throw new MigrationException("Fichier trop volumineux (" + size + " octets, maximum " + MAX_BYTES + ").");
            }
        } catch (IOException e) {
            throw new MigrationException("Fichier illisible (" + e.getClass().getSimpleName() + ").", e);
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(64);
        options.setCodePointLimit((int) MAX_BYTES);
        options.setAllowDuplicateKeys(true);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Object root = yaml.load(reader);
            return root instanceof Map<?, ?> map ? map : null;
        } catch (YAMLException e) {
            throw new MigrationException("YAML invalide ou non sûr (" + e.getClass().getSimpleName() + ").", e);
        } catch (IOException e) {
            throw new MigrationException("Fichier illisible (" + e.getClass().getSimpleName() + ").", e);
        }
    }
}
