package net.idlestate.gradle.downloaddependencies

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gradle-Plugin that creates a local maven repository with all dependencies. 
 */
class DownloadDependenciesPlugin implements Plugin<Project> {

    void apply( Project project ) {
        project.logger.info( 'DownloadDependenciesPlugin.apply( project ) called' )
    }
}

