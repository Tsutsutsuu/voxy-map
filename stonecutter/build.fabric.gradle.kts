
plugins {
    id("dev.kikugie.loom-back-compat")
}

version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = "voxy-map"

val requiredJava = JavaVersion.VERSION_25

repositories {
    mavenCentral()
    maven("https://maven.terraformersmc.com/releases/") { name = "Terraformers" }
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${sc.properties.get<String>("deps.fabric_api")}")

    // Mod Menu is optional: only used for the config button in the mod list
    compileOnly("com.terraformersmc:modmenu:${sc.properties.get<String>("deps.modmenu")}")

    // zstd frames in the Voxy storage are decoded by us
    implementation("com.github.luben:zstd-jni:1.5.6-3")
    include("com.github.luben:zstd-jni:1.5.6-3")
}

loom {
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json")
}

// ---------------------------------------------------------------------------
// Optional in-game acceptance harness. `-Pselftest` adds tools/selftest to the
// main source set so the scripted test (MapSelfTest) is bundled into the jar.
// Normal builds never see that source directory, so the shipped mod has no
// test code; the Fabric entry point installs it reflectively.
// ---------------------------------------------------------------------------
if (providers.gradleProperty("selftest").isPresent) {
    sourceSets.main.get().java.srcDir(rootProject.file("../tools/selftest"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

tasks.processResources {
    val mcCompat = sc.properties.get<String>("mod.mc_compat")
    inputs.property("version", project.version)
    inputs.property("mc_compat", mcCompat)
    filesMatching("fabric.mod.json") { expand(mapOf("version" to project.version, "mc_compat" to mcCompat)) }
}

tasks.named<Jar>("jar") {
    archiveFileName = "voxy-map-${project.version}+fabric${if (providers.gradleProperty("selftest").isPresent) "-selftest" else ""}.jar"
    manifest {
        attributes(
            "Implementation-Title" to "Voxy Map",
            "Implementation-Version" to project.version,
            "Minecraft-Version" to sc.current.version,
            "Mod-Id" to "voxymap"
        )
    }
}
// ---------------------------------------------------------------------------
// Standalone GPU-rendering feasibility spike. This is packaged as a separate
// mod jar (gpu-spike.jar), not part of the voxy-map mod.
// ---------------------------------------------------------------------------
val gpuSpike: SourceSet by sourceSets.creating {
    java.srcDir(rootProject.projectDir.parentFile.resolve("gpu-spike/src/main/java"))
    resources.srcDir(rootProject.projectDir.parentFile.resolve("gpu-spike/src/main/resources"))
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().runtimeClasspath
}

tasks.register<Jar>("gpuSpikeJar") {
    group = "build"
    description = "Builds the standalone GPU cube spike mod"
    archiveFileName = "gpu-spike.jar"
    destinationDirectory = layout.buildDirectory.dir("libs")
    from(gpuSpike.output)
}