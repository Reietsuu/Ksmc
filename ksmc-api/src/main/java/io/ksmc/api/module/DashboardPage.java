package io.ksmc.api.module;

/**
 * Page de module dans le dashboard. {@code bundleResource} est une ressource du JAR (ES module exportant
 * {@code mount(element, api)}) servie sous {@code /modules/<id>/ui.js}.
 */
@Experimental
public record DashboardPage(String title, String icon, String bundleResource) {
}
