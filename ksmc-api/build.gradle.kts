plugins {
    id("ksmc.java21")
}

dependencies {
    api(libs.fabric8.client)
    api(libs.fabric8.generator.annotations)
}


// Génération des CRDs depuis les classes du modèle (source unique de vérité).
val crdgen by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}
dependencies {
    "crdgenImplementation"(libs.fabric8.crd.generator)
}
tasks.named<JavaCompile>("compileCrdgenJava") { options.release = 21 }

val crdDir = rootProject.layout.projectDirectory.dir("deploy/helm/ksmc/crds")
val generateCrds by tasks.registering(JavaExec::class) {
    group = "ksmc"
    description = "Génère les CRDs dans deploy/helm/ksmc/crds"
    classpath = crdgen.runtimeClasspath
    mainClass = "io.ksmc.api.crdgen.GenerateCrds"
    args(crdDir.asFile.absolutePath)
}
val generateCrdsCheck by tasks.registering(JavaExec::class) {
    classpath = crdgen.runtimeClasspath
    mainClass = "io.ksmc.api.crdgen.GenerateCrds"
    val out = layout.buildDirectory.dir("crds-check")
    args(out.get().asFile.absolutePath)
    doFirst { out.get().asFile.deleteRecursively() }
}
val checkCrds by tasks.registering {
    group = "verification"
    description = "Échoue si les CRDs commitées ne correspondent pas au modèle Java"
    dependsOn(generateCrdsCheck)
    val generated = layout.buildDirectory.dir("crds-check")
    doLast {
        val expected = generated.get().asFile.listFiles()!!.associate { it.name to it.readText() }
        val actual = crdDir.asFile.listFiles { f -> f.name.endsWith(".yml") }.orEmpty().associate { it.name to it.readText() }
        if (expected != actual) {
            throw GradleException("CRDs obsolètes : lancer ./gradlew generateCrds et commiter deploy/helm/ksmc/crds")
        }
    }
}
