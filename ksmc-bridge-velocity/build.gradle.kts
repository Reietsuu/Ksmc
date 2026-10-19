plugins {
    id("ksmc.bridge")
}

// L'API Velocity 4 est compilée pour Java 25.
tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

dependencies {
    implementation(project(":ksmc-bridge-common"))
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
}
