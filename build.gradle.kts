plugins {
    id("fabric-loom") version "1.14-SNAPSHOT"
}

base {
    archivesName = properties["archives_base_name"] as String
    version = libs.versions.mod.version.get()
    group = properties["maven_group"] as String
}

repositories {
    mavenCentral()
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
    }
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

val yarnVersion = libs.versions.yarn.mappings.get()

dependencies {
    // Fabric
    minecraft("com.mojang:minecraft:${libs.versions.minecraft.get()}")
    mappings("net.fabricmc:yarn:${yarnVersion}:v2")
    implementation("net.fabricmc:fabric-loader:${libs.versions.fabric.loader.get()}")

    // Meteor
    implementation("meteordevelopment:meteor-client:${libs.versions.meteor.get()}")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
    }
}

fun toMinecraftCompat(version: String): String {
    val match = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?$""")
        .matchEntire(version)
        ?: error("Invalid Minecraft version format: $version. Expected X.Y or X.Y.Z")

    val (major, minor, _) = match.destructured
    return "~$major.$minor"
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "minecraft_version" to toMinecraftCompat(libs.versions.minecraft.get()),
            "jdk_version" to libs.versions.jdk.get(),
        )

        inputs.properties(propertyMap)
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }
    }

    withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:deprecation",
                "-Xlint:unchecked"
            )
        )
    }
}
