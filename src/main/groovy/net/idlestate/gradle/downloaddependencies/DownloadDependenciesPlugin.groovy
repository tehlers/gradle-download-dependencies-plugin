/*
 * Copyright 2015 Thorsten Ehlers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.idlestate.gradle.downloaddependencies

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.util.GradleVersion

/**
 * Gradle-Plugin that creates a local maven repository with all dependencies. 
 */
class DownloadDependenciesPlugin implements Plugin<Project> {
    static final GradleVersion MINIMAL_GRADLE_VERSION = GradleVersion.version( '2.3' )
    static final String DOWNLOAD_DEPENDENCIES_TASK = 'downloadDependencies'

    void apply( Project project ) {
        if ( GradleVersion.current() < MINIMAL_GRADLE_VERSION ) {
            throw new GradleException( "${this.class.simpleName} only works with Gradle >= ${MINIMAL_GRADLE_VERSION}" )
        }

        project.task( DOWNLOAD_DEPENDENCIES_TASK, type: DownloadDependenciesTask, group: 'Build Setup', description: 'Downloads all dependencies into a local directory based repository.' )

        project.afterEvaluate {
            project.tasks[ DOWNLOAD_DEPENDENCIES_TASK ].localRepository = getLocalRepository( project )
        }

        // Use only local repository, if download is not intended
        project.gradle.taskGraph.whenReady { taskGraph ->
            if ( !taskGraph.hasTask( ":${DOWNLOAD_DEPENDENCIES_TASK}" ) ) {
                File repository = getLocalRepository( project )

                project.logger.info( "Replacing all defined repositories with local repository at ${repository}" )

                project.repositories.clear()
                project.repositories {
                    maven {
                        url repository
                    }
                }

                project.buildscript.repositories.clear()
                project.buildscript.repositories {
                    maven {
                        url repository
                    }
                }
            }
        }

        project.configure( project ) {
            extensions.create( 'downloadDependencies', DownloadDependenciesExtension )
        }
    }

    File getLocalRepository( project ) {
        return project.downloadDependencies.localRepository
               ? project.downloadDependencies.localRepository
               : project.file( [ project.rootProject.projectDir, 'gradle', 'repository' ].join( File.separator ) )
    }
}
