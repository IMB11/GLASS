plugins {
    id("dev.kikugie.stonecutter")
    id("net.fabricmc.fabric-loom-remap") version "1.16.1" apply false
    id("net.neoforged.moddev") version "2.0.146" apply false
}

stonecutter active "1.21.1-fabric"

tasks.register("buildAll") {
    group = "build"
    dependsOn(":1.21.1-fabric:build", ":1.21.1-neoforge:build")
}
