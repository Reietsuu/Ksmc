// Projets exécutés côté controller / CLI : Java 21.
plugins {
    id("ksmc.java-base")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}
