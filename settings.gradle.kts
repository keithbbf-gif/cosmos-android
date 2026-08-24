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
        // sherpa-onnx: k2-fsa does NOT publish its Android AAR to Maven Central
        // (verified 404). JitPack republishes the official GitHub-release AAR
        // under com.github.k2-fsa.sherpa-onnx:sherpa-onnx.
        maven("https://jitpack.io")
    }
}

rootProject.name = "cosmos-voice"
include(":app")
