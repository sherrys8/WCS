pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://chaquo.com/maven")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { excludeGroup("dev.sherry.wcs") } }
        mavenCentral { content { excludeGroup("dev.sherry.wcs") } }
        val apiRepository = providers.gradleProperty("wekitPythonApiRepo")
            .orElse(System.getenv("WEKIT_PYTHON_API_REPO") ?: "")
        if (apiRepository.isPresent && apiRepository.get().isNotBlank()) {
            maven {
                name = "WeKitPythonApi"
                url = uri(apiRepository.get())
                content { includeGroup("dev.sherry.wcs") }
            }
        }
    }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "wekit-python-runtime"
include(":runtime")
