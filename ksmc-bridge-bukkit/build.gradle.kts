plugins {
    id("ksmc.bridge")
}

// API Bukkit 1.8.8 et bytecode Java 8 : un seul JAR de 1.8.8 aux dernières versions de Paper.
tasks.withType<JavaCompile>().configureEach {
    options.release = 8
}

dependencies {
    implementation(project(":ksmc-bridge-common"))
    compileOnly(libs.spigot.api)
}
