plugins {
    id("com.android.application")
    // Compose needs the Kotlin Compose compiler plugin. It is applied per-module;
    // its version tracks the Kotlin version used by AGP's built-in Kotlin support.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.notifvault.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.notifvault.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose versions are managed by the Bill of Materials (BOM), so they are
    // never pinned individually here.
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    // Provides collectAsStateWithLifecycle, which stops flows updating while the
    // app is in the background.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.biometric:biometric:1.1.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Material3 no longer bundles the icon set for you, so add it explicitly.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
