plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun escapedConfig(vararg keys: String): String {
    val value = keys.asSequence()
        .map { key ->
            providers.environmentVariable(key)
                .orElse(providers.gradleProperty(key))
                .orElse("")
                .get()
        }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()

    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

val prodBaseUrl = escapedConfig("APP_BASE_URL_PROD", "APP_BASE_URL")
    .ifBlank { "https://api.findocly.com" }
val stagingBaseUrl = escapedConfig("APP_BASE_URL_STAGING")
    .ifBlank { prodBaseUrl }
val sharedDeepLinkScheme = escapedConfig("APP_DEEP_LINK_SCHEME", "SHARED_ANDROID_DEEP_LINK_SCHEME")
    .ifBlank { "memberreward" }
val otpEnabled = escapedConfig("APP_OTP_ENABLED")
    .ifBlank { "false" }
    .equals("true", ignoreCase = true)

android {
    namespace = "com.memberreward.contact"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.memberreward.contact"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEEP_LINK_SCHEME", "\"${sharedDeepLinkScheme.replace("\"", "\\\"")}\"")
        buildConfigField("boolean", "OTP_ENABLED", otpEnabled.toString())
        manifestPlaceholders["deepLinkScheme"] = sharedDeepLinkScheme
    }

    flavorDimensions += "environment"

    productFlavors {
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "APP_BASE_URL", "\"$prodBaseUrl\"")
            buildConfigField("String", "APP_ENVIRONMENT", "\"prod\"")
            resValue("string", "app_name", "Member Reward")
        }

        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("String", "APP_BASE_URL", "\"$stagingBaseUrl\"")
            buildConfigField("String", "APP_ENVIRONMENT", "\"staging\"")
            resValue("string", "app_name", "Member Reward Staging")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
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
        viewBinding = true
        dataBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
}
