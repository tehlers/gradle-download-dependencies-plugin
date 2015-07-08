package net.idlestate.gradle.downloaddependencies

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
    File repository = getProject().file( [ 'gradle', 'repository' ].join( File.separator ) )

    @TaskAction
    def downloadDependencies() {
        repository.mkdirs()

        def libraryFiles = [:]
        def componentIds = [] as Set
        project.configurations.each { configuration ->
            componentIds.addAll( configuration.incoming.resolutionResult.allDependencies.collect { it.selected.id } )

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
        File destinationDirectory = new File( repository, artifactPath.join( File.separator ) )
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

            resolvedParentComponents.each { component ->
                saveArtifacts( component, MavenPomArtifact )
            }
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
    }
}
