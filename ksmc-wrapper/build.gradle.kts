plugins {
    id("ksmc.java8")
}

dependencies {
    implementation(project(":ksmc-wire"))
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
