plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "mk.td.employee"
    compileSdk = 35
    defaultConfig {
        applicationId = "mk.td.employee"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
