plugins {
    id("ksmc.java21")
}

dependencies {
    testImplementation(project(":ksmc-api"))
    testImplementation(libs.logback)
}

// Les tests e2e ne tournent que sur demande explicite (cluster requis) : ./gradlew :ksmc-e2e:test -Pe2e
tasks.test {
    enabled = project.hasProperty("e2e")
    systemProperty("ksmc.namespace", System.getenv("KSMC_NAMESPACE") ?: "ksmc")
    systemProperty("ksmc.examples", rootProject.file("examples").absolutePath)
}
