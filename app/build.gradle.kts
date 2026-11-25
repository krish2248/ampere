import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// keystore.properties (git-ignored) holds the Play upload key. SESSION.md explains how to make one.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// local.properties (git-ignored) may hold the optional live leaderboard backend.
// While it is empty the release app is built WITHOUT the internet permission.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val boardUrl: String = localProps.getProperty("ampere.boardUrl", "").trim().trimEnd('/')
val boardKey: String = localProps.getProperty("ampere.boardKey", "").trim()
val boardOn = boardUrl.isNotEmpty() && boardKey.isNotEmpty()

android {
    namespace = "dev.ampere.battery"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.ampere.battery"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
        buildConfigField("String", "BOARD_URL", "\"$boardUrl\"")
        buildConfigField("String", "BOARD_KEY", "\"$boardKey\"")
    }
    androidResources { localeFilters += listOf("en") }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Release-optimised but debug-signed, for trying the real thing on a phone.
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    sourceSets {
        // Demo data + fake battery live in src/tools and never reach a release build.
        getByName("debug").java.srcDir("src/tools/java")
        getByName("debug").manifest.srcFile("src/tools/AndroidManifest.xml")
        if (boardOn) {
            getByName("release").manifest.srcFile("src/online/AndroidManifest.xml")
            getByName("preview").manifest.srcFile("src/online/AndroidManifest.xml")
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
    packaging { resources.excludes += setOf("META-INF/*.version", "kotlin/**", "DebugProbesKt.bin") }
    dependenciesInfo { includeInApk = false }
    lint { disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi") }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
composeCompiler { stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf")) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splash)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.profileinstaller)
    testImplementation(libs.junit)
}
