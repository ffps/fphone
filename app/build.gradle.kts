plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

// Values passed by the build workflow (see .github/workflows/build.yml):
//   -PfphoneAppId=...        application ID, taken from google-services.json
//   -PfphoneVersionName=X.Y.Z
//   -PfphoneVersionCode=N
val appIdProp = (findProperty("fphoneAppId") as String?) ?: "app.fphone"
val versionNameProp = (findProperty("fphoneVersionName") as String?) ?: "0.0.0-dev"
val versionCodeProp = (findProperty("fphoneVersionCode") as String?)?.toInt() ?: 1

android {
    namespace = "app.fphone"
    compileSdk = 34

    defaultConfig {
        applicationId = appIdProp
        minSdk = 21
        targetSdk = 34
        versionCode = versionCodeProp
        versionName = versionNameProp
    }

    // The release key comes from environment variables set by the workflow.
    // Without them (local builds) the release build is signed with the debug key.
    val keystorePath: String? = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.core:core-ktx:1.13.1")
}
