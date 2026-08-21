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
        // Only consulted when the sample app is pointed at the published SDK artifact
        // (-PuqpaySdkFromMavenLocal=true); see sample-app/build.gradle.kts. Scoped to our
        // own coordinates so nothing else can ever resolve from a developer's ~/.m2.
        mavenLocal {
            content { includeGroup("com.uqpay.sdk") }
        }
    }
}

rootProject.name = "uqpay-sdk-android"

include(":uqpay-sdk")
include(":sample-app")
