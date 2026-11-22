import org.gradle.api.tasks.bundling.Zip
import java.util.zip.ZipFile

plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.19-SNAPSHOT"
}

group = property("maven_group") as String
version = property("mod_version") as String
base { archivesName.set(property("archives_base_name") as String) }

loom {
    runs {
        server {
            server()
            runDirectory = file("run/server")
        }
    }
}

repositories {
    mavenCentral()
    maven { name = "CaffeineMC"; url = uri("https://maven.caffeinemc.net/releases") }
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

dependencies {
    minecraft("com.mojang:minecraft:" + property("minecraft_version"))
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:" + property("loader_version"))
    // Internal chunk encoder/format types are required for the Nvidium-compatible extended terrain vertex ABI.
    modCompileOnly("maven.modrinth:AANobbMI:NFkjnzWE")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val modVersion = project.version.toString()
tasks.processResources {
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") { expand("version" to modVersion) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.test { useJUnitPlatform() }

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

val stillwaterDirectory = layout.projectDirectory.dir("examples/caustiq-stillwater")

val packageStillwater by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the bundled CAUSTIQ Stillwater shader pack as an installable ZIP."
    from(stillwaterDirectory) {
        include("shaders/**", "README.md")
    }
    archiveFileName.set("caustiq-stillwater-" + project.version + ".zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val verifyStillwaterPackage by tasks.registering {
    group = "verification"
    description = "Verifies the generated Stillwater ZIP has a valid shader-pack root and required stages."
    dependsOn(packageStillwater)

    doLast {
        val archive = packageStillwater.get().archiveFile.get().asFile
        check(archive.isFile && archive.length() > 0L) {
            "Stillwater shader-pack ZIP was not generated or is empty: " + archive
        }
        ZipFile(archive).use { zip ->
            val required = setOf("README.md", "shaders/final.fsh", "shaders/final.vsh")
            val actual = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
            check(actual.containsAll(required)) {
                "Stillwater ZIP is missing required root entries. Found: " + actual
            }
            check(actual.none { it.startsWith("caustiq-stillwater/") || it.startsWith("../") || it.startsWith("/") }) {
                "Stillwater ZIP contains an unexpected nested or unsafe root path."
            }
            val fragment = zip.getInputStream(zip.getEntry("shaders/final.fsh")).bufferedReader().use { it.readText() }
            check(fragment.contains("uniform sampler2D DiffuseSampler")
                    && fragment.contains("textureSize(DiffuseSampler, 0)")
                    && fragment.contains("gatherBloom")
                    && fragment.contains("filmicShoulder")) {
                "Stillwater final fragment shader is missing its expected render path or finishing stages."
            }
        }
        logger.lifecycle("Verified Stillwater shader pack: " + archive.absolutePath)
    }
}

val luminaDirectory = layout.projectDirectory.dir("examples/caustiq-lumina")

val packageLumina by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the bundled CAUSTIQ Lumina shader pack (Nvidium branch) as an installable ZIP."
    from(luminaDirectory) {
        include("shaders/**", "README.md")
    }
    archiveFileName.set("caustiq-lumina-" + project.version + ".zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val verifyLuminaPackage by tasks.registering {
    group = "verification"
    description = "Verifies the generated Lumina ZIP has a valid shader-pack root and every required stage."
    dependsOn(packageLumina)

    doLast {
        val archive = packageLumina.get().archiveFile.get().asFile
        check(archive.isFile && archive.length() > 0L) {
            "Lumina shader-pack ZIP was not generated or is empty: " + archive
        }
        ZipFile(archive).use { zip ->
            val required = setOf(
                "README.md", "shaders/shaders.properties", "shaders/block.properties",
                "shaders/gbuffers_terrain.vsh", "shaders/gbuffers_terrain.fsh",
                "shaders/shadow.vsh", "shaders/shadow.fsh",
                "shaders/deferred.fsh", "shaders/composite.fsh", "shaders/final.fsh"
            )
            val actual = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
            check(actual.containsAll(required)) {
                "Lumina ZIP is missing required root entries. Missing: " + (required - actual)
            }
            check(actual.none { it.startsWith("caustiq-lumina/") || it.startsWith("../") || it.startsWith("/") }) {
                "Lumina ZIP contains an unexpected nested or unsafe root path."
            }
        }
        logger.lifecycle("Verified Lumina shader pack: " + archive.absolutePath)
    }
}

tasks.named("build") {
    dependsOn(verifyStillwaterPackage)
    dependsOn(verifyLuminaPackage)
}
