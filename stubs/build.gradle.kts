// Minimal, hand-written API declarations for optional integrations whose artifacts are
// not published to Maven Central. They exist only so the plugin compiles; the real classes
// are provided at runtime by the respective plugins. This module is never shaded.
plugins { `java-library` }

repositories {
    mavenLocal { content { includeModule("io.papermc.paper", "paper-api"); includeModule("com.mojang", "brigadier") } }
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(25) }

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}
