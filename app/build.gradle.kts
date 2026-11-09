import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
val hasReleaseKey = keystorePropsFile.exists()
if (hasReleaseKey) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}

android {
    namespace = "com.mie.kreaworkbench"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mie.kreaworkbench.direct"
        minSdk = 26
        targetSdk = 37
        versionCode = 118
        versionName = "0.6.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

base {
    // round19Ôºö‰∏éÂèëÂ∏É‰ªìÁªü‰∏ÄÔºàÂèëÂ∏É‰ªì‰∏ÄÁõ¥ÊòØ comfy-workbenchÔºâ„Ä?
    // ËøôÊ†∑‰∏§‰∏™‰ªìÂ∫ìÁöÑÊûÑÂª∫‰∫ßÁâ©ÂêåÂêçÔºåÂΩíÊ°£Êó∂‰∏çÁî®ÂÜçÊîπÂêçÔºå‰πüÂ∞ë‰∏Ä‰∏?‰∏§‰ªì‰∏ç‰∏ÄËá?ÁöÑÁÇπ„Ä?
    archivesName.set("comfy-workbench")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha09")
    implementation("dev.chrisbanes.haze:haze:2.0.1")
    // r53 “∫Ã¨≤£¡ß£∫Glass π‹œﬂ”Î core Õ¨∞Ê±æ°£haze-blur “—ÀÊ hazeGlass «–ªª“∆≥˝°£
    implementation("dev.chrisbanes.haze:haze-glass:2.0.1")
    implementation("com.composables:icons-lucide:1.1.0")
    implementation("io.github.dokar3:sonner:0.4.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("androidx.lifecycle:lifecycle-process:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
}
