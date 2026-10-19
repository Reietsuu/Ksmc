// Plugin Bukkit d'exemple utilisant l'API Ksmc (fournie par le bridge installé automatiquement dans chaque service).
plugins {
    java
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/") { content { includeGroup("net.md-5") } }
    maven("https://maven.pkg.github.com/Reietsuu/Ksmc") {
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
        content { includeGroup("io.ksmc") }
    }
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")
    // Fournie par le bridge Ksmc à l'exécution : compileOnly.
    compileOnly("io.ksmc:ksmc-plugin-api:${findProperty("ksmcVersion") ?: "0.1.0-SNAPSHOT"}")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:-options")
}

tasks.jar {
    archiveFileName = "ksmc-plugin-hello.jar"
}
