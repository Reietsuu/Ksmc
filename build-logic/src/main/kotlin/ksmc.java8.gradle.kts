// Projets exécutés dans les serveurs Minecraft (y compris legacy 1.8) : bytecode Java 8.
plugins {
    id("ksmc.java-base")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
}
// JUnit 6 nécessite Java 17 : les tests sont compilés au niveau 17, le code principal reste en Java 8.
tasks.named<JavaCompile>("compileTestJava") {
    options.release = 17
}
