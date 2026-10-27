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
