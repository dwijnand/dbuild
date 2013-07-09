package distributed.project.model
import java.io.File

/**
 * This currently represents a "coordinate" of an artifact, the version you must
 * rewire to depend on, and the amount of time it took to build such an artifact.
 * 
 * NOTE: Build time is only in here so we could hack some performance quickly.
 */
case class ArtifactLocation(info: ProjectRef, version: String, crossSuffix: String, buildTime: Double = 0.0)

/** This class represents an Artifact's SHA (of the file) for unique storage and the 
 * location it has in a maven/ivy/p2 repository.
 * 
 * We use this to push files into artifactory and retrieve them as a workaround now.
 */
case class ArtifactSha(sha: String, location: String)

/** This is the metadata a project generates after building.  We can deploy this to our repository as
 * as an immutable piece of data that is used to retrieve artifacts after the build.
 * 
 * Note: The list of artifacts and files/shas is extracted for each subproject by the build system.
 */
case class ProjectArtifactInfo(
    project: RepeatableProjectBuild,
    // (Subprojects,Relative locations
    versions: Seq[BuildSubArtifactsOut])

/**
 * This represents two pieces of data:
 *
 * (1) The artifacts that we need to rewire dependencies for
 * (2) The repository in which those artifacts are stored.
 *
 * BuildArtifactsIn represents "incoming artifacts to rewire", while
 * BuildArtifactsOut represents the "outgoing artifacts for publication".
 * The latter contains a sequence in which each element contains:
 * - name of a subproject
 * - artifacts published by that subproject
 * - corresponding shas of files published by that subproject to the repository.
 * The set of shas and artifacts should be related, in theory; in practice,
 * the file system is manually inspected, and any additional files that may
 * have been generated (checksums,additional metadata, etc) are grabbed as well
 * and turned into shas.
 * 
 * If the build system has no subproject support, BuildArtifactsOut will contain
 * just one element, where the subproject name is the empty string.
 */
case class BuildArtifactsIn(artifacts: Seq[ArtifactLocation], localRepo: File)
case class BuildArtifactsOut(results: Seq[BuildSubArtifactsOut])
case class BuildSubArtifactsOut(subName: String, artifacts: Seq[ArtifactLocation], shas: Seq[ArtifactSha])


/** This represents general information every dbuild must know:
 * What artifacts are coming in (from metadata) and where to
 * write new artifacts (so we can save them for later).
 * "version" is the version string that will result from the build
 * 
 * Also includes the UUID of this build, in case of direct d-build integration.
 * For subproj, see RepeatableProjectBuild.
 */
case class BuildInput(artifacts: BuildArtifactsIn, uuid: String, version: String, subproj: Seq[String], outRepo: File)
