plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.lernicks0.sleepbird"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.lernicks0.sleepbird"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            // Release signing is supplied locally; no key/password is committed.
            val keyFile = providers.environmentVariable("SLEEPBIRD_KEYSTORE").orNull
            if (keyFile != null) {
                signingConfig = signingConfigs.create("share") {
                    storeFile = file(keyFile)
                    storePassword = providers.environmentVariable("SLEEPBIRD_STORE_PASSWORD").get()
                    keyAlias = "sleepbird"
                    keyPassword = providers.environmentVariable("SLEEPBIRD_KEY_PASSWORD").get()
                }
            }
        }
    }
    lint { abortOnError = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
