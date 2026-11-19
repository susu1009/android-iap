plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.banv.iap.sample"
    compileSdk = 35

    defaultConfig {
        // Real Play purchases need the applicationId of an app uploaded to Play Console
        // (internal track) — override with -PsampleAppId=<id> when testing with license testers.
        val playAppId = providers.gradleProperty("sampleAppId").orNull
        applicationId = playAppId ?: "com.banv.iap.sample"
        // Without a Play Console app id there is nothing to buy from: use FakeIapClient.
        buildConfigField("boolean", "USE_FAKE_IAP", (playAppId == null).toString())
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":iap"))
    implementation(project(":iap-testing"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
