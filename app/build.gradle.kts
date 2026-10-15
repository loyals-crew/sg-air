plugins {
    id("com.android.application")
}

android {
    namespace = "com.hermansantoso.psimonitor"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hermansantoso.psimonitor"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.work:work-runtime:2.10.0")
}
