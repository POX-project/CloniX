plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.cloneforge.nativebridge"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        externalNativeBuild {
            cmake {
                // LSPlant's prefab package ships a shared-STL library
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            // ShadowHook supports thumb/arm32 and arm64 only
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.shadowhook)
    implementation(libs.lsplant)
}
