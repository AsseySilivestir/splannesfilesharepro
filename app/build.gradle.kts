plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.splannes.fileshares"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.splannes.fileshares"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation("org.jmdns:jmdns:3.5.5")
    implementation("com.itextpdf:itext7-core:7.2.5")
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta3")
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    androidTestImplementation(libs.espresso.core)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.2") // ZXing core library
    // NanoHTTPD core
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Optional: webserver module if you need extra features
    implementation("org.nanohttpd:nanohttpd-webserver:2.3.1")
}