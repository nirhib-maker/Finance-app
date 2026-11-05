plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nirhib.kidsreminders"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nirhib.kidsreminders"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.1"
    }

    // חתימה קבועה (אופציונלי): אם מוגדרים משתני הסביבה – ה-APK נחתם תמיד באותו מפתח,
    // כך שאפשר לעדכן גרסה בלי למחוק את האפליקציה. ראו README.
    val ksFile = System.getenv("KIDS_KEYSTORE_FILE")
    signingConfigs {
        if (ksFile != null && file(ksFile).exists()) {
            create("stable") {
                storeFile = file(ksFile)
                storePassword = System.getenv("KIDS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KIDS_KEY_ALIAS")
                keyPassword = System.getenv("KIDS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("stable") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/9/module-info.class",
                "module-info.class",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
