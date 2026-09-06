import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSetContainer

apply(plugin = "maven-publish")

version = property("mod_version").toString()
group = property("maven_group").toString()

val modLoader = property("mod_loader").toString()
val archiveBaseName = property("archives_base_name").toString()
val archiveName = "$archiveBaseName-${property("minecraft_version")}-$modLoader"

extensions.configure<BasePluginExtension> {
    archivesName.set(archiveName)
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter { includeGroup("maven.modrinth") }
    }
}

extensions.configure<SourceSetContainer> {
    remove(getByName("test"))
    named("main") {
        java.srcDir(rootProject.file("src/$modLoader/java"))
        resources.srcDir(rootProject.file("src/$modLoader/resources"))
        resources.srcDir(rootProject.file("src/main/generated"))
    }
}

tasks.matching {
    it.name in setOf(
        "test", "testClasses", "compileTestJava", "processTestResources",
        "stonecutterPrepareTest", "stonecutterGenerateTest", "stonecutterMergeTest"
    )
}.configureEach {
    enabled = false
}

extensions.configure<JavaPluginExtension> {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.withType<ProcessResources>().configureEach {
    filteringCharset = "UTF-8"
    exclude(".cache/**")
}

tasks.named<Jar>("jar") {
    from(rootProject.file("LICENSE")) {
        rename { "${it}_$archiveBaseName" }
    }
}

extensions.configure<PublishingExtension> {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = archiveName
            from(components["java"])
        }
    }
}
