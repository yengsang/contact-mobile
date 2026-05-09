import groovy.json.JsonSlurper

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

val defaultBaseUrl = escapedConfig("APP_BASE_URL").ifBlank { "https://api.yengsang.com" }
val tenantsConfigFile = rootProject.file("tenants.json")
val tenantsConfig = JsonSlurper().parse(tenantsConfigFile) as List<Map<String, Any?>>

fun toKebabUpperSlug(slug: String): String = slug
    .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
    .replace(Regex("[^A-Za-z0-9]+"), "_")
    .uppercase()

fun stringValue(source: Map<String, Any?>, key: String): String =
    source[key]?.toString()?.trim().orEmpty()

data class TenantFlavor(
    val slug: String,
    val appName: String,
    val applicationId: String,
    val brandName: String,
    val apiKeyEnv: String,
)

val tenantFlavors = tenantsConfig.map { entry ->
    val slug = stringValue(entry, "slug")
    require(slug.isNotBlank()) { "Each tenant in tenants.json must include a non-empty slug." }

    val appName = stringValue(entry, "appName")
    require(appName.isNotBlank()) { "Tenant '$slug' must include appName in tenants.json." }

    val applicationId = stringValue(entry, "applicationId")
    require(applicationId.isNotBlank()) { "Tenant '$slug' must include applicationId in tenants.json." }

    val brandName = stringValue(entry, "brandName").ifBlank { appName }
    val apiKeyEnv = stringValue(entry, "apiKeyEnv").ifBlank { "APP_API_KEY_${toKebabUpperSlug(slug)}" }

    TenantFlavor(
        slug = slug,
        appName = appName,
        applicationId = applicationId,
        brandName = brandName,
        apiKeyEnv = apiKeyEnv,
    )
}

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
    }

    flavorDimensions += "tenant"

    productFlavors {
        tenantFlavors.forEach { tenant ->
            create(tenant.slug) {
                dimension = "tenant"
                applicationId = tenant.applicationId
                buildConfigField(
                    "String",
                    "APP_API_KEY",
                    "\"${escapedConfig(tenant.apiKeyEnv, "APP_API_KEY")}\""
                )
                buildConfigField("String", "APP_BASE_URL", "\"$defaultBaseUrl\"")
                buildConfigField("String", "TENANT_SLUG", "\"${tenant.slug}\"")
                buildConfigField("String", "BRAND_NAME", "\"${tenant.brandName.replace("\"", "\\\"")}\"")
            }
        }
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
