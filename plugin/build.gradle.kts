plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.hdsp"
version = project.property("pluginVersion") as String

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // IntelliJ Platform
    intellijPlatform {
        pycharmCommunity(project.property("platformVersion") as String)
        bundledPlugin("PythonCore")
    }

    // HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Note: Kotlin coroutines are provided by IntelliJ Platform

    // Testing
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = "PyCharm Agent"
        version = project.property("pluginVersion") as String

        ideaVersion {
            sinceBuild = project.property("pluginSinceBuild") as String
            untilBuild = project.property("pluginUntilBuild") as String
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
}
