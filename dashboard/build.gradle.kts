// Dashboard web (React + Vite) construit avec le npm du système ; la sortie dist/ est embarquée par le controller.
plugins {
    base
}

val npmSources = files("src", "index.html", "vite.config.ts", "tsconfig.json", "package.json", "package-lock.json")

val npmInstall by tasks.registering(Exec::class) {
    group = "build"
    description = "npm ci"
    inputs.files("package.json", "package-lock.json")
    outputs.file("node_modules/.package-lock.json")
    commandLine("npm", "ci", "--no-audit", "--no-fund", "--loglevel=error")
}

val npmBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Construit le dashboard dans dist/"
    dependsOn(npmInstall)
    inputs.files(npmSources)
    inputs.file("../ksmc-controller/src/main/resources/api/openapi.json")
    outputs.dir("dist")
    commandLine("npm", "run", "build", "--silent")
}

val npmTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Tests Vitest"
    dependsOn(npmInstall, npmBuild)
    inputs.files(npmSources)
    val marker = layout.buildDirectory.file("npm-test.ok")
    outputs.file(marker)
    commandLine("npm", "test", "--silent")
    doLast { marker.get().asFile.apply { parentFile.mkdirs(); writeText("ok") } }
}

tasks.named("assemble") { dependsOn(npmBuild) }
tasks.named("check") { dependsOn(npmTest) }

val dist by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(dist.name, file("dist")) { builtBy(npmBuild) }
}
