// :autoreceiver — the DiAuto Android Auto receiver, merged into DiPlay as a library module.
//
// Every file in this module is derived from DiAuto (https://github.com/shihabal3amri/DiAuto),
// which is AGPL-3.0 and itself a fork of Open Headunit. The original copyright and licence
// headers are kept in each file; see docs/THIRD_PARTY_NOTICES.md for the merged commit and the
// upstream attribution files.
//
// Deliberate differences from DiAuto's app module (see docs/AA_MERGE_M0.md):
//   * This is a library, so there is no applicationId, signing config, distribution flavour or
//     Play-store metadata.
//   * Android mode targets the same devices as DiPlay: minSdk 17, compileSdk 37, Java 11.
//   * The C++ pieces (libusb helper, ffmpeg HEVC software decode, hotspot radio) are NOT built.
//     NDK 29 cannot target API 17 and DiPlay must keep running on Android 4.4. The Java side
//     reaches them only through guarded JNI entry points, so a legacy head unit simply falls
//     back to the platform decoders and to DiPlay's own USB and hotspot code.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.andrerinas.openheadunit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 17
        multiDexEnabled = true
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "AVAILABLE_LOCALES", "\"ar,cs,da,de,es,fi,fr,hu,it,nb,nl,pl,pt,ro,ru,sv,uk,vi,zh\"")
        buildConfigField("String", "APPLICATION_ID", "\"com.shihab.diplay\"")
        buildConfigField("int", "VERSION_CODE", "0")
        buildConfigField("String", "VERSION_NAME", "\"0.3.12\"")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        // Upstream code is not lint-clean; the module's own correctness is covered by the
        // shared JVM tests in :common and by the DiPlay CI builds.
        abortOnError = false
        checkReleaseBuilds = false
        checkDependencies = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation("com.google.protobuf:protobuf-java:3.25.1")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.3.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.3.5")
    implementation("com.google.android.material:material:1.10.0")

    // TLS 1.2 on Android 4.4/5.x, which the Android Auto handshake requires.
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // Wireless pairing helpers. Play services Nearby is deliberately absent: it requires
    // minSdk 21 while DiPlay still ships to Android 4.4. NearbyManager.kt is a stand-in that
    // reports unavailability, and discovery falls back to Wi-Fi Direct / Bluetooth / network.
    implementation("com.google.zxing:core:3.5.3")

    // Root/shell access. Deliberately absent: Shizuku's provider requires minSdk 23 and libsu
    // raises the same floor, while DiPlay still ships to Android 4.4 and already has its own ADB
    // backend. SUExecutor.kt is a stand-in that reports "no privileged backend".
    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")

    // Upstream loading screen artwork. No annotation processor: DiAuto's code never used the
    // generated GlideApp API, only Glide.with().
    implementation("com.github.bumptech.glide:glide:4.16.0")

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}

// Analysing the vendored upstream sources (300+ files plus 108k generated protobuf lines) took
// longer than the entire test suite, and every finding would be upstream's to fix rather than
// DiPlay's. DiPlay's own modules keep their lint gates; this one is covered by the JVM tests in
// :shared/:common and by the three assembles.
tasks.matching { it.name.startsWith("lintAnalyze") }.configureEach { enabled = false }
