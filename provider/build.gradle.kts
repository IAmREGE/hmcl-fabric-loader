import java.security.MessageDigest

plugins {
    java
}

val fabricLoaderVersion = property("fabric_loader_version") as String
val mixinVersion = property("mixin_version") as String
val asmVersion = property("asm_version") as String
val accessWidenerVersion = property("access_widener_version") as String

dependencies {
    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    implementation("net.fabricmc:sponge-mixin:$mixinVersion")
    implementation("net.fabricmc:access-widener:$accessWidenerVersion")
}

val hmclAddOpens = listOf(
    "java.base/java.lang",
    "java.base/java.lang.reflect",
    "java.base/jdk.internal.loader",
    "javafx.base/com.sun.javafx.binding",
    "javafx.base/com.sun.javafx.event",
    "javafx.base/com.sun.javafx.runtime",
    "javafx.base/javafx.beans.property",
    "javafx.graphics/javafx.css",
    "javafx.graphics/javafx.stage",
    "javafx.graphics/javafx.scene",
    "javafx.graphics/com.sun.glass.ui",
    "javafx.graphics/com.sun.javafx.stage",
    "javafx.graphics/com.sun.javafx.util",
    "javafx.graphics/com.sun.prism",
    "javafx.controls/com.sun.javafx.scene.control",
    "javafx.controls/com.sun.javafx.scene.control.behavior",
    "javafx.graphics/com.sun.javafx.tk.quantum",
    "javafx.controls/javafx.scene.control",
    "javafx.controls/javafx.scene.control.skin",
    "jdk.attach/sun.tools.attach"
).joinToString(" ")

base {
    archivesName.set("hmcl-fabric-provider")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "net.fabricmc.loader.impl.launch.knot.KnotClient",
            "Implementation-Title" to "HMCL Fabric GameProvider",
            "Implementation-Version" to project.version,
            "Add-Opens" to hmclAddOpens,
            "Enable-Native-Access" to "ALL-UNNAMED",
            "Enable-Final-Field-Mutation" to "ALL-UNNAMED"
        )
        attributes(mapOf(
            "Implementation-Title" to "ASM",
            "Implementation-Version" to asmVersion,
            "Implementation-Vendor" to "OW2"
        ), "org/objectweb/asm/")
    }
}

/**
 * Builds an all-in-one executable bootstrapper JAR
 * (`hmcl-fabric-loader-all.jar`). Drop this JAR into the same directory as any
 * `HMCL*.jar` and run: `java -jar hmcl-fabric-loader-all.jar`
 */
val fatJar = tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles a standalone executable JAR containing Fabric Loader, Mixin, and HmclGameProvider."
    archiveBaseName.set("hmcl-fabric-loader")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Main-Class" to "net.fabricmc.loader.impl.launch.knot.KnotClient",
            "Implementation-Title" to "HMCL Fabric Loader Bootstrapper",
            "Implementation-Version" to project.version,
            "Add-Opens" to hmclAddOpens,
            "Enable-Native-Access" to "ALL-UNNAMED",
            "Enable-Final-Field-Mutation" to "ALL-UNNAMED"
        )
        attributes(
            mapOf(
                "Implementation-Title" to "ASM",
                "Implementation-Version" to asmVersion,
                "Implementation-Vendor" to "OW2"
            ),
            "org/objectweb/asm/"
        )
    }

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().filter {
        it.name.endsWith(".jar")
    }.map(::zipTree)) {
        exclude(
            "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
            "META-INF/MANIFEST.MF"
        )
        // Exclude Fabric Loader's built-in Minecraft game provider — we
        // replace it with HmclGameProvider. This removes both the
        // MinecraftGameProvider class and its services registration so Knot's
        // findEmbeddedGameProvider() fast path reads only our single-line
        // HmclGameProvider entry.
        exclude("net/fabricmc/loader/impl/game/minecraft/**")
        exclude("META-INF/services/net.fabricmc.loader.impl.game.GameProvider")

        // Exclude compile-time only metadata, proguard configs, and unused
        // annotation processor tools
        exclude("META-INF/maven/**")
        exclude("META-INF/proguard/**")
        exclude("fabric-installer*.json")
        exclude("org/spongepowered/tools/**")

        // Exclude completely unused Guava modules that are never touched by
        // Fabric Loader or Mixin
        exclude("ui/**")
    }
}

val generatedResourcesDir = layout.buildDirectory.dir("generated/resources")
val generateBootstrapDependencies =
tasks.register("generateBootstrapDependencies") {
    inputs.files(configurations.runtimeClasspath)
    outputs.dir(generatedResourcesDir)

    doLast {
        val file = generatedResourcesDir.get()
                   .file("META-INF/hmcl-fabric-dependencies.txt").asFile
        file.parentFile.mkdirs()
        file.bufferedWriter().use { writer ->
            val md = MessageDigest.getInstance("SHA-1")
            for (artifact in configurations.runtimeClasspath.get()
                             .resolvedConfiguration.resolvedArtifacts) {
                val id = artifact.moduleVersion.id
                val artifactFile = artifact.file
                val sha1 = artifactFile.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        md.update(buf, 0, read)
                    }
                    md.digest().joinToString("") { b ->
                        String.format("%02x", b)
                    }
                }
                val repo = if (id.group.startsWith("net.fabricmc")) {
                    "https://maven.fabricmc.net/"
                } else {
                    "https://repo1.maven.org/maven2/"
                }
                writer.write(
                    "${id.group}\t${id.name}\t${id.version}\t${sha1}\t${repo}\n"
                )
            }
        }
    }
}

/**
 * Assembles the ultra-lightweight bootstrapper (< 30 KB). Contains only
 * HmclGameProvider and Bootstrap downloader, with zero bundled dependencies!
 */
val bootstrapJar = tasks.register<Jar>("bootstrapJar") {
    group = "build"
    description = "Assembles the ultra-lightweight executable bootstrapper (< 30 KB) that auto-downloads dependencies."
    archiveBaseName.set("hmcl-fabric-bootstrapper")
    archiveClassifier.set("")

    manifest {
        attributes(
            "Main-Class" to "rege.rege.hmclmods.loader.game.bootstrap.Bootstrap",
            "Implementation-Title" to "HMCL Fabric Loader Lightweight Bootstrapper",
            "Implementation-Version" to project.version
        )
    }

    from(sourceSets.main.get().output)
    from(generatedResourcesDir)
    dependsOn(generateBootstrapDependencies)
}

tasks.named("assemble") {
    dependsOn(fatJar)
    dependsOn(bootstrapJar)
}
