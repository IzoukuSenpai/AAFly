// AAFly — plugin Paper de vol temporisé.
// Developped by AzurHOSTS.com and powered by AzurWare.fr.
plugins {
    java
}

group = "com.azurhosts"
version = "1.0.0"
description = "Plugin Paper de vol temporisé par AzurHOSTS (reproduction clean-room de wFly)."

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    compileOnly(libs.paper.api)
    // Bibliothèques runtime chargées par AAFlyLoader (MavenLibraryResolver), jamais shadées.
    compileOnly(libs.hikaricp)
    compileOnly(libs.sqlite.jdbc)
    compileOnly(libs.mysql.connector.j)
    compileOnly(libs.configurate.yaml)
    // Soft-depends
    compileOnly(libs.placeholderapi)
    compileOnly(libs.luckperms.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.paper.api)
    testImplementation(libs.configurate.yaml)
    // Tests du stockage (dépôt SQLite réel sur fichier temporaire)
    testImplementation(libs.hikaricp)
    testImplementation(libs.sqlite.jdbc)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-parameters"))
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    // Versions injectées : paper-plugin.yml (version du plugin) et la liste des bibliothèques
    // runtime lue par AAFlyLoader (une seule source de vérité : le version catalog).
    val props = mapOf(
        "version" to project.version.toString(),
        "hikaricpVersion" to libs.versions.hikaricp.get(),
        "sqliteJdbcVersion" to libs.versions.sqlite.jdbc.get(),
        "mysqlConnectorVersion" to libs.versions.mysql.connector.j.get(),
        "configurateVersion" to libs.versions.configurate.get(),
    )
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching(listOf("paper-plugin.yml", "aafly-libraries.txt")) {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.jar {
    archiveBaseName.set("AAFly")
    manifest {
        attributes(
            "Implementation-Title" to "AAFly",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "AzurHOSTS",
        )
    }
}

// ---------------------------------------------------------------- API publique (SPEC-phase2 B2)
// build/libs/AAFly-api-<version>.jar (+ -sources, -javadoc) : uniquement com/azurhosts/aafly/api/**,
// pour que des plugins tiers compilent contre l'API sans embarquer AAFly.

val apiPackages = "com/azurhosts/aafly/api/**"

/**
 * Vérifie que les classes de l'API ne référencent que l'API elle-même, Paper API et le JDK : analyse des
 * constant pools (noms internes "a/b/C" et descripteurs "La/b/C;" y figurent en clair, ASCII).
 */
abstract class VerifyApiIsolation : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apiClasses: ConfigurableFileCollection

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun verify() {
        val own = "com/azurhosts/aafly/"
        val allowed = "com/azurhosts/aafly/api/"
        // Bibliothèques runtime d'AAFly et soft-depends : interdites dans l'API.
        val forbidden = listOf("org/spongepowered/", "com/zaxxer/", "org/sqlite/", "com/mysql/",
            "me/clip/", "net/luckperms/")
        val violations = mutableListOf<String>()
        val classes = apiClasses.files.filter { it.isFile && it.name.endsWith(".class") }
        if (classes.isEmpty()) {
            throw GradleException("Aucune classe d'API trouvée (com/azurhosts/aafly/api)")
        }
        for (file in classes) {
            val text = String(file.readBytes(), Charsets.ISO_8859_1)
            var i = text.indexOf(own)
            while (i >= 0) {
                if (!text.startsWith(allowed, i)) {
                    val end = text.indexOfAny(charArrayOf(';', '<', '\u0000', ')'), i).let { if (it < 0) text.length else it }
                    violations += "${file.name} -> ${text.substring(i, minOf(end, i + 120)).filter { it.code in 32..126 }}"
                }
                i = text.indexOf(own, i + own.length)
            }
            forbidden.filter { text.contains(it) }.forEach { violations += "${file.name} -> $it" }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("L'API publique dépend de types internes ou de bibliothèques non-Paper :\n  "
                + violations.distinct().joinToString("\n  "))
        }
        marker.get().asFile.writeText("OK ${classes.size} classes\n")
    }
}

val verifyApiIsolation = tasks.register<VerifyApiIsolation>("verifyApiIsolation") {
    group = "verification"
    description = "Vérifie que com.azurhosts.aafly.api ne dépend que de Paper API et du JDK."
    apiClasses.from(sourceSets.main.map { it.output.classesDirs.asFileTree.matching { include(apiPackages) } })
    marker.set(layout.buildDirectory.file("tmp/verifyApiIsolation/ok.txt"))
}

val apiJar = tasks.register<Jar>("apiJar") {
    group = "build"
    description = "Assemble AAFly-api-<version>.jar (API publique uniquement)."
    dependsOn(verifyApiIsolation)
    archiveBaseName.set("AAFly-api")
    from(sourceSets.main.map { it.output.classesDirs }) { include(apiPackages) }
    from("LICENSE") { into("META-INF") }
    manifest {
        attributes(
            "Implementation-Title" to "AAFly API",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "AzurHOSTS",
        )
    }
}

val apiSourcesJar = tasks.register<Jar>("apiSourcesJar") {
    group = "build"
    description = "Sources de l'API publique AAFly."
    archiveBaseName.set("AAFly-api")
    archiveClassifier.set("sources")
    from(sourceSets.main.map { it.allJava }) { include(apiPackages) }
    from("LICENSE") { into("META-INF") }
}

val apiJavadoc = tasks.register<Javadoc>("apiJavadoc") {
    group = "documentation"
    description = "Javadoc de l'API publique AAFly (hors ligne : aucun lien réseau)."
    source(sourceSets.main.map { it.allJava.matching { include(apiPackages) } })
    classpath = files(sourceSets.main.map { it.compileClasspath })
    destinationDir = layout.buildDirectory.dir("docs/api-javadoc").get().asFile
    (options as StandardJavadocDocletOptions).apply {
        windowTitle = "AAFly API ${project.version}"
        docTitle = "AAFly API ${project.version} — AzurHOSTS"
    }
}

val apiJavadocJar = tasks.register<Jar>("apiJavadocJar") {
    group = "build"
    description = "Javadoc de l'API publique AAFly."
    archiveBaseName.set("AAFly-api")
    archiveClassifier.set("javadoc")
    from(apiJavadoc)
}

tasks.assemble { dependsOn(apiJar, apiSourcesJar, apiJavadocJar) }
tasks.check { dependsOn(verifyApiIsolation) }
