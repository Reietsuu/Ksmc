plugins {
    id("ksmc.bridge")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
}

dependencies {
    implementation(project(":ksmc-bridge-common"))
    compileOnly(libs.bungeecord.api)
}
