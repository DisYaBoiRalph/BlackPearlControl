plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    jvmToolchain(11)
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
    }
}
