package net.idlestate.gradle.downloaddependencies

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

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
            for ( component in resolvedComponents ) {
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
        logger.info( "Saving artifacts of ${artifactsResult.id.toString()}" )

        artifactTypes.each { artifactType ->
            artifactsResult.getArtifacts( artifactType ).each { artifact ->

                if ( artifact.hasProperty( 'file' ) ) {
                    copyArtifactFileToRepository( artifactsResult.id, artifact.file )
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
}
