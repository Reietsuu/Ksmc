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
    implementation(libs.logback)
    testImplementation(libs.fabric8.server.mock)
}

application {
    mainClass = "io.ksmc.controller.KsmcController"
}
