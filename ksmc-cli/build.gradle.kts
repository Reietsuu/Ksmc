// CLI `ksmc` : client de l'API REST du controller (picocli, client HTTP du JDK, Jackson).
plugins {
    id("ksmc.java21")
    application
}

dependencies {
    implementation(libs.picocli)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
    implementation(libs.jline)
    // Tests : serveur WebSocket de la console.
    testImplementation(libs.javalin)
    testImplementation(libs.logback)
}

application {
    mainClass = "io.ksmc.cli.Main"
    applicationName = "ksmc"
}

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

