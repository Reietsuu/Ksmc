// Module Ksmc d'exemple : route REST, commande CLI, page du dashboard, écouteur d'événements, label de Pod.
plugins {
    java
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
    // API Ksmc publiée (GitHub Packages exige un jeton GitHub avec read:packages, même pour un dépôt public).
    maven("https://maven.pkg.github.com/Reietsuu/Ksmc") {
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
        content { includeGroup("io.ksmc") }
    }
}

dependencies {
    // Fournie par le controller à l'exécution : ne pas l'embarquer.
    compileOnly("io.ksmc:ksmc-api:${findProperty("ksmcVersion") ?: "0.1.0-SNAPSHOT"}")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveFileName = "ksmc-module-hello.jar"
}
