import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "id.vern.antigravity"
    compileSdk = 36

    val localProperties = Properties().apply {
        val propFile = rootProject.file("local.properties")
        if (propFile.exists()) {
            propFile.inputStream().use { load(it) }
        }
    }

    defaultConfig {
        applicationId = "id.vern.antigravity"
        minSdk = 26
        targetSdk = 28
        versionCode = 3
        versionName = "1.0.2"

        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("int", "GATEWAY_PORT", "45161")
        buildConfigField("int", "CORE_PORT", "45158")
        val oauthClientId = (localProperties.getProperty("oauth.clientId") ?: System.getenv("ANTIGRAVITY_OAUTH_CLIENT_ID") ?: "").replace("\"", "\\\"")
        val oauthClientSecret = (localProperties.getProperty("oauth.clientSecret") ?: System.getenv("ANTIGRAVITY_OAUTH_CLIENT_SECRET") ?: "").replace("\"", "\\\"")
        buildConfigField("String", "OAUTH_CLIENT_ID", "\"$oauthClientId\"")
        buildConfigField("String", "OAUTH_CLIENT_SECRET", "\"$oauthClientSecret\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("zip", "gz", "tar")
    }

    val keystorePath = localProperties.getProperty("signing.storeFile")
        ?: (project.findProperty("storeFile") as String?)
        ?: "KuatoDev.jks"
    val resolvedKeystore = file(keystorePath)

    signingConfigs {
        create("release") {
            if (resolvedKeystore.exists()) {
                storeFile = resolvedKeystore
                storePassword = localProperties.getProperty("signing.storePassword")
                    ?: (project.findProperty("storePassword") as String?)
                    ?: System.getenv("SIGNING_STORE_PASSWORD")
                    ?: ""
                keyAlias = localProperties.getProperty("signing.keyAlias")
                    ?: (project.findProperty("alias") as String?)
                    ?: System.getenv("SIGNING_KEY_ALIAS")
                    ?: ""
                keyPassword = localProperties.getProperty("signing.keyPassword")
                    ?: (project.findProperty("keyPassword") as String?)
                    ?: System.getenv("SIGNING_KEY_PASSWORD")
                    ?: ""
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
}

