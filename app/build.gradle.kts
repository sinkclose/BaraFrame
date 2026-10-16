plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val splitApks = providers.gradleProperty("splitApks").orNull == "true"
val signingKeystore = providers.environmentVariable("BARAFRAME_KEYSTORE").orNull

android {
    namespace = "com.multicast.bara"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.multicast.bara"
        minSdk = 23
        targetSdk = 36
        versionCode = providers.gradleProperty("buildVersionCode").orNull?.toInt() ?: 5
        versionName = providers.gradleProperty("buildVersionName").orNull ?: "1.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingKeystore != null) {
            create("ci") {
                storeFile = file(signingKeystore)
                storePassword = providers.environmentVariable("BARAFRAME_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("BARAFRAME_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("BARAFRAME_KEY_PASSWORD").get()
            }
        }
    }

    splits {
        abi {
            isEnable = splitApks
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingKeystore != null) signingConfig = signingConfigs.getByName("ci")
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
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.work)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
