pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.1.21"
        id("org.jetbrains.kotlin.jvm") version "2.1.21"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kids-reminders"

include(":core")
// בסביבה בלי Android SDK אפשר להריץ רק את בדיקות הליבה:  gradle -PcoreOnly=true :core:test
if (providers.gradleProperty("coreOnly").orNull != "true") {
    include(":app")
}
