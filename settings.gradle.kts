pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        mavenCentral()
    }
}

rootProject.name = "hmcl-fabric-loader"

include(":provider")
