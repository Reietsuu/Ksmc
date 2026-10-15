plugins {
    id("ksmc.java21")
    application
}

dependencies {
    implementation(project(":ksmc-api"))
    implementation(project(":ksmc-wire"))
    implementation(libs.josdk)
    implementation(libs.javalin)
    implementation(libs.jnats)
    // jnats tire bcprov-lts8on, qui duplique les classes de bcprov-jdk18on (utilisé par fabric8) : une seule variante.
    implementation(libs.bcprov)
    implementation(libs.logback)
    implementation(libs.jgit)
    implementation(libs.commons.compress)
    testImplementation(libs.fabric8.server.mock)
}

application {
    mainClass = "io.ksmc.controller.KsmcController"
}

configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-lts8on")
}
