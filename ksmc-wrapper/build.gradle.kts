plugins {
    id("ksmc.java8")
}

dependencies {
    implementation(project(":ksmc-wire"))
    implementation(project(":ksmc-messaging-nats"))
    testImplementation(libs.testcontainers)
}

// BouncyCastle n'est utilisé par jnats que pour l'authentification NKeys ; Ksmc utilise des mots de passe.
// L'exclure garde le wrapper léger (les tests le vérifient en se connectant sans lui).
configurations.all {
    exclude(group = "org.bouncycastle")
}

// JAR autonome (dépendances incluses), copié dans les images runtime.
tasks.jar {
    archiveFileName = "ksmc-wrapper.jar"
    manifest { attributes("Main-Class" to "io.ksmc.wrapper.Main") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/versions/**", "module-info.class")
    }
}
