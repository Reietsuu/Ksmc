plugins {
    id("ksmc.java8")
}

dependencies {
    api(project(":ksmc-messaging-nats"))
    testImplementation(libs.testcontainers)
}

configurations.all {
    exclude(group = "org.bouncycastle")
}
