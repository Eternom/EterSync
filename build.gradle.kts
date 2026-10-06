plugins {
    id("java-library")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    // EterLib : compilé depuis GitHub
    maven("https://jitpack.io")
    // Repli : EterLib publié sur cette machine (`gradlew publishToMavenLocal` dans EterLib), pour tester avant de pousser
    mavenLocal()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")

    // Socle commun : base, Redis, langue, menus (plugin EterLib installé sur le serveur)
    compileOnly("com.github.Eternom:EterLib:1.1.1")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
