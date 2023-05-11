enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()

        gradlePluginPortal()

        // Geyser, Floodgate, Cumulus etc.
        maven("https://repo.opencollab.dev/main")
        maven("https://repo.opencollab.dev/maven-snapshots")

        // Minecraft
        maven("https://libraries.minecraft.net") {
            name = "minecraft"
            mavenContent {
                releasesOnly()
            }
        }

        // Forge
        maven("https://maven.minecraftforge.net/")

        // Fabric
        maven("https://maven.fabricmc.net/")

        // Sponge Snapshots
        maven("https://repo.spongepowered.org/repository/maven-public/")

        // MCProtocolLib
        maven("https://jitpack.io") {
            content {
                includeGroupByRegex("com\\.github\\..*")
            }
        }

        // For Adventure snapshots
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()

        maven("https://repo.opencollab.dev/maven-snapshots")
        maven("https://repo.spongepowered.org/repository/maven-public/")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.fabricmc.net/")
    }
}

rootProject.name = "hydraulic-parent"

include("shared", "fabric", "forge")

include(":bedrock-pack-schema")
include(":schema-generator")

project(":bedrock-pack-schema").projectDir = file("pack-schema/bedrock")
project(":schema-generator").projectDir = file("pack-schema/generator")