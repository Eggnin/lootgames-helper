plugins {
  id("java-library")
  id("com.gtnewhorizons.retrofuturagradle") version "2.0.2"
}

group = "com.lgsudoku"
version = "1.2.0"

java {
  toolchain {
    // 1.7.10 runs on Java 8; Gradle itself may run on a newer JDK.
    languageVersion.set(JavaLanguageVersion.of(8))
    vendor.set(org.gradle.jvm.toolchain.JvmVendorSpec.AZUL)
  }
}

minecraft {
  mcVersion.set("1.7.10")
  username.set("Developer")
}

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
}

tasks.processResources.configure {
  val projVersion = project.version.toString()
  inputs.property("version", projVersion)
  filesMatching("mcmod.info") {
    expand(mapOf("modVersion" to projVersion))
  }
}

repositories {
  maven {
    name = "GTNH Maven"
    url = uri("https://nexus.gtnewhorizons.com/repository/public/")
  }
}

// LootGames is deliberately NOT a compile-time dependency: every piece of the
// integration goes through reflection on class/field/method names, so one jar
// keeps working across LootGames builds (GTNH ships its own forks).
