allprojects {
    group = "org.jackhuang.hmcl.fabric"
    version = "1.0.0+loader.${property("fabric_loader_version")}"

    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") {
            name = "FabricMC"
        }
        maven("https://repo.spongepowered.org/maven/") {
            name = "SpongePowered"
        }
    }
}

subprojects {
    apply(plugin = "java")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
