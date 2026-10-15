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
        maven("https://repo.papermc.io/repository/maven-public/") {
            content {
                includeGroup("com.velocitypowered")
                includeGroup("net.md-5")
                includeGroup("io.papermc.paper")
            }
        }
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
            content {
                includeGroup("org.spigotmc")
                includeGroup("org.bukkit")
            }
        }
        maven("https://libraries.minecraft.net/") {
            content { includeGroup("com.mojang") }
        }
        maven("https://oss.sonatype.org/content/repositories/snapshots/") {
            content { includeGroup("net.md-5") }
        }
    }
}

rootProject.name = "ksmc"

include(
    "ksmc-api",
    "ksmc-wire",
    "ksmc-wrapper",
    "ksmc-messaging-nats",
    "ksmc-bridge-common",
    "ksmc-bridge-velocity",
    "ksmc-bridge-bungee",
    "ksmc-controller",
    "ksmc-e2e",
)
