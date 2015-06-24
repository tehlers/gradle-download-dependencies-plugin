package net.idlestate.gradle.downloaddependencies

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * Gradle-Task that downloads all dependencies into a local directory based repository.
 */
class DownloadDependenciesTask extends DefaultTask {

    @TaskAction
    def downloadDependencies() {
        logger.info( 'To be continued...' )
    }
}
