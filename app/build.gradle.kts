plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import java.io.File

val env = System.getenv()

android {
    namespace = "dev.cloneforge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.cloneforge"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("ci") {
            val ks = env["CF_KEYSTORE_FILE"]
            if (ks != null && File(ks).exists() &&
                env["CF_KEYSTORE_PASS"] != null && env["CF_KEY_PASS"] != null &&
                env["CF_KEY_ALIAS"] != null
            ) {
                storeFile = file(ks)
                storePassword = env["CF_KEYSTORE_PASS"]
                keyAlias = env["CF_KEY_ALIAS"]
                keyPassword = env["CF_KEY_PASS"]
            }
        }
    }

    buildTypes {
        release {
            val ks = env["CF_KEYSTORE_FILE"]
            val ciSignable = ks != null && File(ks).exists() &&
                    env["CF_KEYSTORE_PASS"] != null && env["CF_KEY_PASS"] != null &&
                    env["CF_KEY_ALIAS"] != null
            signingConfig = if (ciSignable) {
                signingConfigs.getByName("ci")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES",
            "META-INF/versions/**"
        )
    }
}

dependencies {
    implementation(project(":patcher"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.service)
    implementation(libs.coroutines.android)
}
