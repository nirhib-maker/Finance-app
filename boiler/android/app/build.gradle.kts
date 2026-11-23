plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nirhib.boiler"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nirhib.boiler"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.3.0"
    }

    // חתימה קבועה (אופציונלי) – כמו ב-kids-reminders: אותו מפתח בכל בנייה, כדי שאפשר לעדכן בלי למחוק.
    val ksFile = System.getenv("BOILER_KEYSTORE_FILE")
    signingConfigs {
        if (ksFile != null && file(ksFile).exists()) {
            create("stable") {
                storeFile = file(ksFile)
                storePassword = System.getenv("BOILER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BOILER_KEY_ALIAS")
                keyPassword = System.getenv("BOILER_KEY_PASSWORD")
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

    // The web app (boiler/index.html, css, js) is bundled as-is, so both share one UI and one model.js.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webAssets"))

    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/module-info.class", "module-info.class")
        }
    }
}

val copyWebApp by tasks.registering(Sync::class) {
    from(rootProject.file("..")) {
        include("index.html", "manifest.webmanifest", "icon.svg", "css/**", "js/**")
    }
    into(layout.buildDirectory.dir("generated/webAssets/web"))
}
tasks.named("preBuild") { dependsOn(copyWebApp) }

dependencies {
    implementation(project(":core"))
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.webkit:webkit:1.12.1")
}
