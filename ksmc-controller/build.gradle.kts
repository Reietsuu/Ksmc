plugins {
    id("ksmc.java21")
    application
}

dependencies {
    implementation(project(":ksmc-api"))
    implementation(project(":ksmc-wire"))
    implementation(project(":ksmc-messaging-nats"))
    implementation(libs.josdk)
    implementation(libs.javalin)
    implementation(libs.jnats)
    // jnats tire bcprov-lts8on, qui duplique les classes de bcprov-jdk18on (utilisé par fabric8) : une seule variante.
    implementation(libs.bcprov)
    implementation(libs.logback)
    implementation(libs.jgit)
    implementation(libs.commons.compress)
    testImplementation(libs.fabric8.server.mock)
}

application {
    mainClass = "io.ksmc.controller.KsmcController"
}

// JARs des bridges servis aux services (copiés dans /opt/ksmc/bridges de l'image).
val bridges by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    for (bridge in listOf("velocity", "bungee")) {
        bridges(project(mapOf("path" to ":ksmc-bridge-$bridge", "configuration" to "shadowRuntimeElements")))
    }
}
distributions {
    main {
        contents {
            from(bridges) {
                into("bridges")
                rename { name -> name.replace(Regex("-[0-9][^-]*(-SNAPSHOT)?\\.jar$"), ".jar") }
            }
        }
    }
}

configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-lts8on")
}
