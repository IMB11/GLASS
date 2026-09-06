plugins {
    id("dev.kikugie.stonecutter")
    id("net.fabricmc.fabric-loom-remap")
}

extra["mod_loader"] = "fabric"
apply(from = rootProject.file("build.gradle.kts"))

val minecraftVersion = property("minecraft_version").toString()
val loaderVersion = property("loader_version").toString()
val fabricVersion = property("fabric_version").toString()
val libguiVersion = property("libgui_version").toString()
val sodiumVersion = property("sodium_fabric_version").toString()

repositories {
    maven("https://maven.ladysnake.org/releases")
    maven("https://staging.alexiil.uk/maven/") {
        content { includeGroup("io.github.cottonmc") }
    }
    maven("https://maven.shedaniel.me")
}

loom {
    accessWidenerPath.set(rootProject.file("src/fabric/resources/glass.accesswidener"))
    runs.configureEach {
        runDir("run/${project.name}")
    }
}

fabricApi {
    configureDataGeneration {
        client.set(true)
        outputDirectory.set(rootProject.file("src/main/generated"))
        addToResources.set(false)
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
    modImplementation(include("io.github.cottonmc:LibGui:$libguiVersion")!!)
    modCompileOnly("maven.modrinth:sodium:$sodiumVersion")
    if (project.hasProperty("with_sodium")) {
        modLocalRuntime("maven.modrinth:sodium:$sodiumVersion")
    }
}

tasks.processResources {
    val metadata = mapOf(
        "version" to project.version,
        "minecraft_version" to minecraftVersion,
        "loader_version" to loaderVersion
    )
    inputs.properties(metadata)
    filesMatching("fabric.mod.json") {
        expand(metadata)
    }
}

tasks.named<JavaExec>("runDatagen") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
