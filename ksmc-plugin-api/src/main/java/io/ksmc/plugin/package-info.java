/**
 * API Ksmc pour les plugins et mods : point d'entrée {@link io.ksmc.plugin.Ksmc#get()}. Disponible quand le bridge
 * Ksmc est installé (automatique dans un service Ksmc). Les résultats asynchrones sont des {@code CompletableFuture}
 * complétés sur le thread principal du serveur.
 */
package io.ksmc.plugin;
