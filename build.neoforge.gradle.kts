plugins {
    id("dev.kikugie.stonecutter")
    id("net.neoforged.moddev")
}

extra["mod_loader"] = "neoforge"
apply(from = rootProject.file("build.gradle.kts"))

val minecraftVersion = property("minecraft_version").toString()
val neoforgeVersion = property("neoforge_version").toString()
val sodiumVersion = property("sodium_neoforge_version").toString()

neoForge {
    enable {
        version = neoforgeVersion
        setDisableRecompilation(true)
    }
    accessTransformers.from(rootProject.file("src/neoforge/resources/META-INF/accesstransformer.cfg"))
    runs {
        create("client") {
            client()
            gameDirectory.set(rootProject.file("run/neoforge-client"))
        }
        create("server") {
            server()
            gameDirectory.set(rootProject.file("run/neoforge-server"))
            programArgument("--nogui")
        }
    }
    mods {
        create("glass") {
            sourceSet(sourceSets.main.get())
        }
    }
}

val sodiumDistribution by configurations.creating {
    isTransitive = false
}

val extractSodium by tasks.registering(Sync::class) {
    from(provider { zipTree(sodiumDistribution.singleFile) }) {
        include("META-INF/jarjar/net.caffeinemc.sodium-*-mod.jar")
        eachFile { path = name }
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("dependencies/sodium"))
}

dependencies {
    sodiumDistribution("maven.modrinth:sodium:$sodiumVersion")
    compileOnly(files(fileTree(layout.buildDirectory.dir("dependencies/sodium")) {
        include("*.jar")
    }).builtBy(extractSodium))
    if (project.hasProperty("with_sodium")) {
        runtimeOnly("maven.modrinth:sodium:$sodiumVersion")
    }
}

tasks.named("createMinecraftArtifacts") {
    dependsOn("stonecutterGenerate")
}

tasks.processResources {
    val metadata = mapOf(
        "version" to project.version,
        "minecraft_version" to minecraftVersion,
        "neoforge_version" to neoforgeVersion
    )
    inputs.properties(metadata)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(metadata)
    }
}
