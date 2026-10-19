plugins {
    id("ksmc.java8")
}

dependencies {
    api(project(":ksmc-messaging-nats"))
    api(project(":ksmc-plugin-api"))
    testImplementation(libs.testcontainers)
}

configurations.all {
    exclude(group = "org.bouncycastle")
}
