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

import com.google.common.io.Files

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

import org.gradle.jvm.JvmLibrary
import org.gradle.language.base.artifact.SourcesArtifact
import org.gradle.language.java.artifact.JavadocArtifact
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact

/**
 * Gradle-Task that downloads all dependencies into a local directory based repository.
 */
class DownloadDependenciesTask extends DefaultTask {
    File localRepository

    @TaskAction
    def downloadDependencies() {
        localRepository.mkdirs()

        def libraryFiles = [:]
        def componentIds = [] as Set
        ( project.configurations + project.buildscript.configurations ).each { configuration ->
            componentIds.addAll(
                configuration.incoming.resolutionResult.allDependencies.collect {
                    if ( it.hasProperty( 'selected' ) ) {
                        return it.selected.id
                    }

                    if ( it.hasProperty( 'attempted' ) ) {
                        logger.warn( "Unable to save artifacts of ${it.attempted.displayName}" )
                    }
                }
            )

            configuration.incoming.files.each { file ->
                libraryFiles[ file.name ] = file
            }
        }

        logger.info( "Dependencies of all configurations: ${componentIds.collect { it.toString() }.join( ', ' ) }" )

        componentIds.each { component ->
            def fileName = "${component.module}-${component.version}.jar"
            if ( libraryFiles.containsKey( fileName.toString() ) ) {
                copyArtifactFileToRepository( component, libraryFiles[ fileName ] )
            } else {
                logger.warn( "Library file ${fileName} of dependency ${component.toString()} not found." )
            }
        }

        [ (MavenModule.class): [ MavenPomArtifact.class ] as Class[],
          (JvmLibrary.class): [ SourcesArtifact.class, JavadocArtifact.class ] as Class[] ].each { module, artifactTypes ->
            def resolvedComponents = resolveComponents( componentIds, module, artifactTypes )
            resolvedComponents.each { component ->
                saveArtifacts( component, artifactTypes )
            }
        }
    }

    def resolveComponents( componentIds, module, artifactTypes ) {
        return project.dependencies.createArtifactResolutionQuery()
               .forComponents( componentIds )
               .withArtifacts( module, artifactTypes )
               .execute().resolvedComponents
    }

    def saveArtifacts( artifactsResult, artifactTypes ) {
        logger.debug( "Saving artifacts of ${artifactsResult.id.toString()}" )

        artifactTypes.each { artifactType ->
            artifactsResult.getArtifacts( artifactType ).each { artifact ->

                if ( artifact.hasProperty( 'file' ) ) {
                    copyArtifactFileToRepository( artifactsResult.id, artifact.file )

                    if ( artifactType == MavenPomArtifact.class ) {
                        resolveParents( artifact.file )
                    }
                }

                if ( artifact.hasProperty( 'failure' ) ) {
                    logger.warn( artifact.failure.message )
                }
            }
        }
    }

    def copyArtifactFileToRepository( id, source ) {
        logger.info( "Saving artifact file ${source.name} of ${id.toString()}" )

        def artifactPath = id.group.split( '\\.' ) + id.module + id.version
        File destinationDirectory = new File( localRepository, artifactPath.join( File.separator ) )
        destinationDirectory.mkdirs()

        File destination = new File( destinationDirectory, source.name )

        destination.withOutputStream { os ->
            source.withInputStream { is ->
                os << is
            }
        }
    }

    def resolveParents( pom ) {
        XmlSlurper parser = new XmlSlurper()
        parser.setFeature( 'http://apache.org/xml/features/nonvalidating/load-external-dtd', false )
        parser.setFeature( "http://apache.org/xml/features/disallow-doctype-decl", false )

        def document = parser.parse( pom )
        if ( !document.parent.isEmpty() ) {
            def componentId = new ParentComponentIdentifier( document.parent )

            logger.info( "Resolving parent ${componentId.displayName}" )
            def resolvedParentComponents = project.dependencies.createArtifactResolutionQuery()
                                           .forComponents( componentId )
                                           .withArtifacts( MavenModule, MavenPomArtifact )
                                           .execute().resolvedComponents

            if ( resolvedParentComponents.isEmpty() ) {
                // For unknown reasons parent poms are only resolvable from local repositories.
                // If they are missing in local repositories, they are therefore downloaded by
                // constructing urls with the definied repositories.
                downloadParent( componentId )
            } else {
                resolvedParentComponents.each { component ->
                    saveArtifacts( component, MavenPomArtifact )
                }
            }
        }
    }

    def downloadParent( id ) {
        boolean found = project.repositories.find { repository ->
            if ( repository.hasProperty( 'url' ) ) {
                String fileName = "${id.module}-${id.version}.pom"
                def artifactPath = id.group.split( '\\.' ) + id.module + id.version + fileName
                URL url = new URL( "${repository.url}${artifactPath.join( '/' )}" )

                File tempDir = Files.createTempDir()
                tempDir.deleteOnExit()
                File localPomFile = new File( tempDir, fileName )
                localPomFile.deleteOnExit()

                try {
                    localPomFile.withOutputStream { os ->
                        url.withInputStream { is ->
                            os << is
                        }
                    }

                    copyArtifactFileToRepository( id, localPomFile )
                    resolveParents( localPomFile )

                    logger.info( "Downloaded ${id.displayName} from ${url}" )

                    return true
                } catch ( FileNotFoundException e ) {
                    logger.debug( "${id.displayName} not found at ${url}" )
                }
            }

            return false
        }

        if ( !found ) {
            logger.warn( "Unable to find pom file of ${id.displayName}" )
        }
    }

    static final class ParentComponentIdentifier implements ModuleComponentIdentifier {
        String _group
        String _module
        String _version

        ParentComponentIdentifier( parent ) {
            _group = parent.groupId
            _module = parent.artifactId
            _version = parent.version
        }

        String getGroup() {
            return _group
        }

        String getModule() {
            return _module
        }

        String getVersion() {
            return _version
        }

        String getDisplayName() {
            return "${_group}:${_module}:${_version}"
        }

        String toString() {
            return getDisplayName()
        }
    }
}
