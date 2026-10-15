plugins {
    id("net.neoforged.moddev")
    id("ksmc.bridge")
}

// Minecraft 26.x : Java 25, noms officiels Mojang (code partagé avec Fabric).
tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

neoForge {
    version = "26.3.0.83"
}

sourceSets.main {
    java.srcDir("../ksmc-bridge-vanilla/src/main/java")
}

dependencies {
    implementation(project(":ksmc-bridge-common"))
    shadow(project(":ksmc-bridge-common"))
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    configurations = listOf(project.configurations.getByName("shadow"))
}
