plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.19-SNAPSHOT"
}

group = property("maven_group") as String
version = property("mod_version") as String
base {
    archivesName.set(property("archives_base_name") as String)
}

repositories {
    // Loom supplies the Minecraft and Fabric repositories.
    // Add repositories here only when a real external dependency is introduced.
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
}

val modVersion = project.version.toString()
tasks.processResources {
    inputs.property("version", modVersion)

    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}
