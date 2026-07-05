import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.json)
    implementation("org.hid4java:hid4java:0.8.0")
}

compose.desktop {
    application {
        mainClass = "com.fossyaudio.bpcontrol.desktop.MainKt"
        javaHome =
            javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(17))
            }.get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "BlackPearlControl"
            windows {
                iconFile = project.file("src/main/resources/icon.ico")
            }
        }
    }
}
