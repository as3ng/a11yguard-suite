pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FraudIntelligence"

// :a11yguard  -> the drop-in detection SDK (Android library / AAR)
// :harness    -> the authorized automation test-rig (installable app, test instrumentation only)
include(":a11yguard", ":harness")
