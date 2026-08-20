// Standalone settings for the PUBLIC MIRROR repo (notibaseorg/notibase-android).
// The release-mobile-sdks workflow flattens the library module to the repo
// root so JitPack publishes the clean coordinate
//   com.github.notibaseorg:notibase-android:<tag>
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "notibase-android"
