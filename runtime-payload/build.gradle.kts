plugins {
    alias(libs.plugins.android.library)
}

android {
    // Pure-Java payload library: dexed at CI time and injected into clone APKs.
    // It must NOT depend on AndroidX or Kotlin so the payload dex stays tiny and self-contained.
    namespace = "dev.cloneforge.runtime"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Java wrapper API for the ART hook engine (classes must land in the payload dex)
    compileOnly(libs.lsplant)
    compileOnly(libs.bouncycastle)
    compileOnly(libs.androidx.annotation)
}
