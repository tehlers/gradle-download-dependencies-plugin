package net.idlestate.gradle.downloaddependencies

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gradle-Plugin that creates a local maven repository with all dependencies. 
 */
class DownloadDependenciesPlugin implements Plugin<Project> {

    void apply( Project project ) {
        project.task( 'downloadDependencies', type: DownloadDependenciesTask, group: 'Build Setup', description: 'Downloads all dependencies into a local directory based repository.' )
    }
}

