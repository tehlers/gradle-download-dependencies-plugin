package net.idlestate.gradle.downloaddependencies

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gradle-Plugin that creates a local maven repository with all dependencies. 
 */
class DownloadDependenciesPlugin implements Plugin<Project> {
    static final String DOWNLOAD_DEPENDENCIES_TASK = 'downloadDependencies'

    void apply( Project project ) {
        project.task( DOWNLOAD_DEPENDENCIES_TASK, type: DownloadDependenciesTask, group: 'Build Setup', description: 'Downloads all dependencies into a local directory based repository.' )

        // Use only local repository, if download is not intended
        project.gradle.taskGraph.whenReady { taskGraph ->
            if ( !taskGraph.hasTask( ":${DOWNLOAD_DEPENDENCIES_TASK}" ) ) {
                project.logger.info( "Replacing all defined repositories with local repository at 'gradle/repository'" )

                project.repositories.clear()
                project.repositories {
                    maven {
                        url 'gradle/repository'
                    }
                }
            }
        }
    }
}
