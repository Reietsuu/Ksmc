plugins {
    id("net.fabricmc.fabric-loom")
    id("ksmc.bridge")
}

// Minecraft 26.x : non obfusqué, Java 25 ; pas de mappings.
val minecraftVersion = "26.3"
val minecraftLine = "26.1"
tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

sourceSets.main {
    java.srcDir("../ksmc-bridge-vanilla/src/main/java")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.163.0+$minecraftVersion")
    implementation(project(":ksmc-bridge-common"))
    shadow(project(":ksmc-bridge-common"))
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    configurations = listOf(project.configurations.getByName("shadow"))
}

tasks.processResources {
    inputs.property("minecraft", minecraftLine)
    filesMatching("fabric.mod.json") { expand("minecraft" to minecraftLine) }
}
