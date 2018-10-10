/*
 * Copyright 2015-2018 Thorsten Ehlers
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

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

import org.gradle.jvm.JvmLibrary
import org.gradle.language.base.artifact.SourcesArtifact
import org.gradle.language.java.artifact.JavadocArtifact
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact

import org.unbescape.html.HtmlEscape

import org.xml.sax.SAXParseException

/**
 * Gradle-Task that downloads all dependencies into a local directory based repository.
 */
class DownloadDependenciesTask extends DefaultTask {
    @OutputDirectory
    File localRepository

    @TaskAction
    def downloadDependencies() {
        localRepository.mkdirs()

        downloadDependenciesForProject( project )
        project.subprojects.each {
            downloadDependenciesForProject( it )
        }
    }

    void downloadDependenciesForProject( Project currentProject ) {
        def libraryFiles = [:]
        def componentIds = [] as Set
        ( currentProject.configurations + currentProject.buildscript.configurations ).each { configuration ->
            if ( isConfigurationResolvable( configuration ) ) {
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
        }

        logger.info( "Dependencies of all configurations: ${componentIds.collect { it.toString() }.join( ', ' ) }" )

        componentIds.each { component ->
            if ( component instanceof ModuleComponentIdentifier ) {
                findMatchingLibraries( libraryFiles, component ).each { library ->
                    if ( library != null ) {
                        copyArtifactFileToRepository( component, library )
                    }
                }
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

    /**
     * Gradle 3.4 introduced the configuration 'apiElements' that isn't resolvable. So
     * we have to check before accessing it.
     */
    boolean isConfigurationResolvable( configuration ) {
        if ( !configuration.metaClass.respondsTo( configuration, 'isCanBeResolved' ) ) {
            // If the recently introduced method 'isCanBeResolved' is unavailable, we
            // assume (for now) that the configuration can be resolved.
            return true
        }

        return configuration.isCanBeResolved()
    }

    def findMatchingLibraries( libraryFiles, component ) {
        def libraries = [] as Set

        String fileNameWithoutExtension = "${component.module}-${component.version}"

        libraryFiles.each { key, value ->
            if ( key.startsWith( fileNameWithoutExtension ) ) {
                libraries << value
            }
        }

        if ( libraries.isEmpty() ) {
            logger.warn( "Library file ${component.module}-${component.version}.jar of dependency ${component.toString()} not found even when considering potential classifiers." )
        }

        return libraries
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
        def artifactPath = id.group.split( '\\.' ) + id.module + id.version
        File destinationDirectory = new File( localRepository, artifactPath.join( File.separator ) )
        destinationDirectory.mkdirs()

        File destination = new File( destinationDirectory, source.name )
        if ( destination.exists() ) {
            logger.debug( "${destination.absolutePath} already exists - skipping" )
            return
        }

        logger.info( "Saving artifact file ${source.name} of ${id.toString()} to ${destination.absolutePath}" )

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

        def document

        try {
            document = parser.parse( pom )
        } catch( final SAXParseException e ) {
            // Some POM files like plexus-1.0.4.pom are using undeclared entities. These entities will be replaced by their unicode equivalent.
            def xml = HtmlEscape.unescapeHtml( pom.text )
            document = parser.parseText( xml )
        }

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
                URL url = new URL( "${repository.url}${repository.url.toString().endsWith( '/' ) ? '' : '/'}${artifactPath.join( '/' )}" )

                File tempDir = project.mkdir( DownloadDependenciesUtils.getTemporaryDirectory() )
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
                } finally {
                    project.delete( tempDir )
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

        @Override
        String getGroup() {
            return _group
        }

        @Override
        String getModule() {
            return _module
        }

        @Override
        String getVersion() {
            return _version
        }

        @Override
        ModuleIdentifier getModuleIdentifier() {
            return new DefaultModuleIdentifier( _group, _module )
        }

        String getDisplayName() {
            return "${_group}:${_module}:${_version}"
        }

        String toString() {
            return getDisplayName()
        }
    }
}
