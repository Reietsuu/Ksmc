pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/") { content { includeGroupAndSubgroups("net.fabricmc") } }
        maven("https://maven.neoforged.net/releases/") { content { includeGroupAndSubgroups("net.neoforged") } }
    }
    plugins {
        id("net.fabricmc.fabric-loom") version "1.19.1"
        id("net.neoforged.moddev") version "2.0.148"
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
    "ksmc-plugin-api",
    "ksmc-bridge-common",
    "ksmc-bridge-velocity",
    "ksmc-bridge-bungee",
    "ksmc-bridge-bukkit",
    "ksmc-bridge-fabric",
    "ksmc-bridge-neoforge",
    "ksmc-controller",
    "ksmc-cli",
    "ksmc-e2e",
    "dashboard",
)
