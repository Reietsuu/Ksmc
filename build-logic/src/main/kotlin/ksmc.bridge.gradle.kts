// Bridges (plugins/mods) : JAR autonome avec dépendances relocalisées pour éviter les conflits avec le serveur.
plugins {
    id("ksmc.java-base")
    id("com.gradleup.shadow")
}

configurations.all {
    exclude(group = "org.bouncycastle")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier = ""
    relocate("io.nats", "io.ksmc.lib.nats")
    relocate("com.google.gson", "io.ksmc.lib.gson")
    exclude("META-INF/versions/**", "module-info.class", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    mergeServiceFiles()
}
tasks.named("jar") { enabled = false }
tasks.named("assemble") { dependsOn("shadowJar") }
