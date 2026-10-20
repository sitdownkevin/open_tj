plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val projectVersion = rootProject.file("VERSION").readText().trim()
require(Regex("""\d+\.\d{1,2}\.\d{1,2}""").matches(projectVersion)) {
    "Android VERSION must be major.minor.patch; minor and patch must be below 100"
}
val versionParts = projectVersion.split(".").map(String::toInt)
val androidVersionCode = versionParts[0].toLong() * 10000 + versionParts[1] * 100 + versionParts[2]
require(androidVersionCode in 1..2100000000L) { "Android versionCode is out of range" }

android {
    namespace = "io.github.sitdownkevin.opentj"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.sitdownkevin.opentj"
        minSdk = 26
        targetSdk = 36
        versionCode = androidVersionCode.toInt()
        versionName = projectVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
