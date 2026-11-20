plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
    // Server internals (mojang-mapped) are needed for the AI bots' fake players.
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

val ktor_version: String by project

group = "dev.betrix"

version = "0.1.0-ai-items2-20261120"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc-repo" }
    maven("https://repo.flyte.gg/releases")
    maven { url = uri("https://repo.panda-lang.org/releases") }
    maven { url = uri("https://repo.codemc.io/repository/maven-releases/") }
    maven { url = uri("https://repo.codemc.io/repository/maven-snapshots/") }
    maven("https://mvn.lib.co.nz/public") {
        content { includeGroup("me.libraryaddict.disguises") }
    }
}

dependencies {
    paperweight.paperDevBundle("26.3.build.245-stable")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("gg.flyte:twilight:1.1.19")
    implementation("dev.rollczi:litecommands-bukkit:3.9.7")
    implementation("com.github.shynixn.mccoroutine:mccoroutine-bukkit-api:2.22.0")
    implementation("com.github.shynixn.mccoroutine:mccoroutine-bukkit-core:2.22.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.michael-bull.kotlin-result:kotlin-result:2.0.1")
    compileOnly("me.libraryaddict.disguises:libsdisguises:26.10.24")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.charleskorn.kaml:kaml:0.85.0")
    implementation("com.squareup.okio:okio:3.10.2")
    implementation("io.insert-koin:koin-core:3.5.6")
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    implementation("io.ktor:ktor-client-logging:${ktor_version}")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")

    // Testing (MockBukkit has no 26.3 build, so the MockBukkit-based LangServiceTest was removed)
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.insert-koin:koin-test:3.5.6")
    testImplementation("io.insert-koin:koin-test-junit5:3.5.6")
}

tasks {
    runServer {
        minecraftVersion("26.3")
    }

    compileJava { options.compilerArgs.add("-parameters") }

    build { dependsOn("shadowJar") }

    // Keep the installed filename stable so upgrades replace the existing plugin.
    shadowJar { archiveFileName.set("super-smash-mobs-brawl-0.1.0-ai-all.jar") }

    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") { expand(props) }
    }

    withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("junit.jupiter.testinstance.lifecycle.default", "per_class")
    }
}

val targetJavaVersion = 25

kotlin { jvmToolchain(targetJavaVersion) }
