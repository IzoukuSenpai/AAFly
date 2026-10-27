/**
 * API publique stable d'AAFly, plugin Paper de vol temporisé par AzurHOSTS.
 *
 * <p>Point d'entrée : {@link com.azurhosts.aafly.api.AAFlyApi}, obtenue par
 * {@link com.azurhosts.aafly.api.AAFlyProvider#get()} ou via le {@code ServicesManager} de Bukkit.
 * Les plugins tiers compilent contre {@code AAFly-api-<version>.jar} ({@code compileOnly}, jamais embarqué) :
 * ce paquet ne dépend que de Paper API et du JDK.
 *
 * <p>Règles : appels sur le main thread uniquement ; aucune méthode ne bloque ; compatibilité vérifiable avec
 * {@link com.azurhosts.aafly.api.ApiVersion#isCompatibleWith}.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
package com.azurhosts.aafly.api;
