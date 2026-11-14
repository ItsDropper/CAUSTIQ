plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.19-SNAPSHOT"
}
group = property("maven_group") as String
version = property("mod_version") as String
base { archivesName.set(property("archives_base_name") as String) }
repositories {
    mavenCentral()
    maven { name = "CaffeineMC"; url = uri("https://maven.caffeinemc.net/releases") }
}
dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modCompileOnly("net.caffeinemc:sodium-fabric-api:0.8.0+mc1.21.11")
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
