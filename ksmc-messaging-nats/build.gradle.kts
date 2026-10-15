plugins {
    id("ksmc.java8")
}

dependencies {
    api(project(":ksmc-wire"))
    api(libs.jnats)
    testImplementation(libs.testcontainers)
}
