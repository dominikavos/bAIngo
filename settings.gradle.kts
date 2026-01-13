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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()

        val gitLabPrivateToken: String? by settings
        neatGitlabServicesContent(62338280, "no.neat", "devkit", gitLabPrivateToken)
    }
}

// Copied from neat-home
fun RepositoryHandler.neatGitlabServicesContent(project: Int, group: String, moduleName: String, gitLabPrivateToken: String?) {
    maven {
        setUrl("https://gitlab.com/api/v4/projects/${project}/packages/maven")
        name = "NeatGitLabServices"

        authentication {
            create("header", HttpHeaderAuthentication::class.java)
        }

        credentials(HttpHeaderCredentials::class.java) {
            if (System.getenv("CI_JOB_TOKEN") != null) {
                name = "Job-Token"
                value = System.getenv("CI_JOB_TOKEN")
            } else {
                name = "Private-Token"
                value = gitLabPrivateToken
            }
        }
        content {
            includeModule(group, moduleName)
        }
    }
}

rootProject.name = "MeetingBingo"
include(":app")
