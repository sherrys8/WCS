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
        val apiRepository = providers.gradleProperty("wcsPythonApiRepo")
            .orElse(System.getenv("WCS_PYTHON_API_REPO") ?: "")
        if (apiRepository.isPresent && apiRepository.get().isNotBlank()) {
            maven {
                name = "WcSPythonApi"
                url = uri(apiRepository.get())
                content { includeGroup("dev.sherry.wcs") }
            }
        }
    }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "wcs-python-runtime"
include(":runtime")
