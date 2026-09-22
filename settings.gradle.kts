pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

val insta360MavenUser =
    providers.gradleProperty("insta360MavenUser")
        .orElse(providers.environmentVariable("INSTA360_MAVEN_USER"))
val insta360MavenPassword =
    providers.gradleProperty("insta360MavenPassword")
        .orElse(providers.environmentVariable("INSTA360_MAVEN_PASSWORD"))

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "insta360"
            url = uri("https://androidsdk.insta360.com/repository/maven-public/")
            credentials {
                username = insta360MavenUser.orNull
                password = insta360MavenPassword.orNull
            }
        }
    }
}

rootProject.name = "Insta-auto_adjust"
include(":app")
 
