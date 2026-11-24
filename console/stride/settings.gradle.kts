pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        // GeckoView (gecko flavor only) is published here, not on Maven Central.
        // The AAR is ~290 MB, so it is referenced, never vendored into the repo.
        maven { url = uri("https://maven.mozilla.org/maven2") }
    }
}
rootProject.name = "Stride"
include(":app")
