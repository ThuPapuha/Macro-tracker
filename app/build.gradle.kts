import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/** Build-time settings come from environment variables set by GitHub Actions (or local.properties). */
fun setting(name: String, default: String = ""): String {
    val localFile = rootProject.file("local.properties")
    val local = if (localFile.exists()) {
        Properties().apply { localFile.inputStream().use { load(it) } }.getProperty(name)
    } else {
        null
    }
    return (System.getenv(name) ?: local ?: default).trim()
}

fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "io.github.thupapuha.macrotracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.thupapuha.macrotracker"
        minSdk = 26
        targetSdk = 35
        versionCode = setting("VERSION_CODE", "1").toIntOrNull() ?: 1
        versionName = "1.0.${setting("VERSION_CODE", "0")}"

        buildConfigField("String", "SUPABASE_URL", setting("SUPABASE_URL").quoted())
        buildConfigField("String", "SUPABASE_ANON_KEY", setting("SUPABASE_ANON_KEY").quoted())
        buildConfigField("String", "GEMINI_MODEL", setting("GEMINI_MODEL", "gemini-3.5-flash-lite").quoted())
        // GitHub repo whose releases the app checks for updates (set automatically in Actions).
        buildConfigField("String", "UPDATE_REPO", setting("GITHUB_REPOSITORY", "ThuPapuha/Macro-tracker").quoted())
    }

    signingConfigs {
        create("release") {
            // A private keystore can be supplied through secrets; otherwise the repo keystore keeps
            // every build signed with the same key so updates install over the previous version.
            val customStore = setting("ANDROID_KEYSTORE_PATH")
            if (customStore.isNotEmpty()) {
                storeFile = file(customStore)
                storePassword = setting("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = setting("ANDROID_KEY_ALIAS")
                keyPassword = setting("ANDROID_KEY_PASSWORD").ifEmpty { setting("ANDROID_KEYSTORE_PASSWORD") }
            } else {
                storeFile = file("signing/macrotracker.jks")
                storePassword = "macrotracker"
                keyAlias = "macrotracker"
                keyPassword = "macrotracker"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
}
