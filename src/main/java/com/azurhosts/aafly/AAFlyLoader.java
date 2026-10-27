package com.azurhosts.aafly;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Chargeur Paper : déclare les bibliothèques runtime (HikariCP, sqlite-jdbc, mysql-connector-j,
 * configurate-yaml et leurs dépendances transitives) résolues depuis Maven Central par Paper.
 * Aucun shading, aucun téléchargement effectué par le plugin lui-même.
 *
 * <p>Les coordonnées viennent de la ressource {@code aafly-libraries.txt}, générée au build depuis le
 * version catalog (source unique de vérité). Les modules déjà fournis par Paper (SLF4J) et les modules
 * inutiles (protobuf de MySQL X DevAPI) sont exclus.
 */
public final class AAFlyLoader implements PluginLoader {

    private static final String LIBRARIES_RESOURCE = "aafly-libraries.txt";
    private static final Pattern COORDINATE = Pattern.compile("^[A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+$");

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder("central", "default",
                MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());

        List<Exclusion> exclusions = List.of(
                new Exclusion("org.slf4j", "*", "*", "*"),
                new Exclusion("com.google.protobuf", "*", "*", "*"));
        for (String coordinate : readCoordinates()) {
            resolver.addDependency(new Dependency(new DefaultArtifact(coordinate), null, false, exclusions));
        }
        classpathBuilder.addLibrary(resolver);
    }

    private static List<String> readCoordinates() {
        InputStream in = AAFlyLoader.class.getClassLoader().getResourceAsStream(LIBRARIES_RESOURCE);
        if (in == null) {
            throw new IllegalStateException("Ressource " + LIBRARIES_RESOURCE + " absente du jar AAFly.");
        }
        List<String> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String l = line.trim();
                if (l.isEmpty() || l.startsWith("#")) {
                    continue;
                }
                if (!COORDINATE.matcher(l).matches()) {
                    throw new IllegalStateException("Coordonnée Maven invalide dans " + LIBRARIES_RESOURCE + " : " + l);
                }
                out.add(l);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Lecture de " + LIBRARIES_RESOURCE + " impossible", e);
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Aucune bibliothèque déclarée dans " + LIBRARIES_RESOURCE);
        }
        return out;
    }
}
