import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
}

group = "dev.ultimatecombat"
version = "1.0.0"
description = "Advanced, fully YAML-configurable combat management plugin."

repositories {
    // A locally built Paper API (see INSTALLATION.md, "Building from source") is preferred when present.
    mavenLocal { content { includeModule("io.papermc.paper", "paper-api"); includeModule("com.mojang", "brigadier") } }
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

// Runtime libraries resolved by the server from Maven Central through plugin.yml "libraries".
val runtimeLibraries = listOf(
    "com.zaxxer:HikariCP:6.2.1",
    "org.mariadb.jdbc:mariadb-java-client:3.5.1",
    "redis.clients:jedis:5.2.0",
)

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    compileOnly(project(":stubs"))
    runtimeLibraries.forEach { compileOnly(it) }

    testImplementation("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:${property("mockBukkitVersion")}")
    testImplementation(project(":stubs"))
    runtimeLibraries.forEach { testImplementation(it) }
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-parameters"))
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "description" to project.description.toString(),
        "libraries" to runtimeLibraries.joinToString("\n") { "  - $it" },
    )
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

tasks.jar {
    archiveBaseName.set("UltimateCombat")
    manifest {
        attributes(
            "Implementation-Title" to "UltimateCombat",
            "Implementation-Version" to project.version,
        )
    }
}
