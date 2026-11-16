rootProject.name = "LootGamesHelper"

pluginManagement {
  repositories {
    maven {
      name = "GTNH Maven"
      url = uri("https://nexus.gtnewhorizons.com/repository/public/")
      mavenContent {
        includeGroupByRegex("com\\.gtnewhorizons\\..+")
        includeGroup("com.gtnewhorizons")
      }
    }
    gradlePluginPortal()
    mavenCentral()
    mavenLocal()
  }
}

plugins {
  // Automatic toolchain provisioning (JDK 8 for compiling a 1.7.10 mod)
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
