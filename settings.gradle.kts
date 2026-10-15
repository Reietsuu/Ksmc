pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ksmc"

include(
    "ksmc-api",
    "ksmc-wire",
    "ksmc-wrapper",
    "ksmc-messaging-nats",
    "ksmc-controller",
    "ksmc-e2e",
)
