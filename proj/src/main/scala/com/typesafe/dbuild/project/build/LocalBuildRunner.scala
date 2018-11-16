package com.typesafe.dbuild.project.build

import com.typesafe.dbuild.model._
import com.typesafe.dbuild.logging.Logger
import com.typesafe.dbuild.project.resolve.ProjectResolver
import Logger.prepareLogMsg
import java.io.File
import com.typesafe.dbuild.repo.core._
import com.typesafe.dbuild.adapter.Adapter
import Adapter.{IO,toFF}
import Adapter.Path._
import Adapter.syntaxio._
import com.typesafe.dbuild.project.dependencies.Extractor
import com.typesafe.dbuild.project.cleanup.Recycling.{ updateTimeStamp, markSuccess }
import com.typesafe.dbuild.project.BuildData
import com.typesafe.dbuild.utils.TrackedProcessBuilder
import BuildDirs._

/**
 * This class encodes the logic to resolve a project and run its build given
 * a local repository, a resolver and a build runner.
 */
class LocalBuildRunner(builder: BuildRunner,
  val extractor: Extractor,
  val repository: Repository) {

  def checkCacheThenBuild(target: File, build: RepeatableProjectBuild, tracker: TrackedProcessBuilder,
      outProjects: Seq[Project], children: Seq[BuildOutcome], buildData: BuildData, exp: CleanupExpirations): BuildOutcome = {
    val log = buildData.log
    try {
      val artifactsOut = LocalRepoHelper.getPublishedDeps(build.uuid, repository, log) // will throw exception if not in cache yet
      LocalRepoHelper.debugArtifactsInfo(artifactsOut, log)
      BuildUnchanged(build.config.name, children, artifactsOut)
    } catch {
      case t: RepositoryException =>
        log.debug("Failed to resolve: " + build.uuid + " from " + build.config.name)
        //log.trace(t)
        BuildSuccess(build.config.name, children, runLocalBuild(target, build, tracker, outProjects,
          buildData, exp))
    }
  }

  def runLocalBuild(target: File, build: RepeatableProjectBuild, tracker: TrackedProcessBuilder,
    outProjects: Seq[Project], buildData: BuildData, exp: CleanupExpirations): BuildArtifactsOut =
    useProjectUniqueBuildDir(build.config.name + "-" + build.uuid, target) { dir =>
      try {
        updateTimeStamp(dir)
        // extractor.resolver.resolve() only resolves the main URI,
        // extractor.dependencyExtractor.resolve() also resolves the nested ones, recursively
        // here we only resolve the ROOT project, as we will later call the runBuild()
        // of the build system, which in turn will call checkCacheThenBuild(), above, on all subprojects,
        // which will again call this method, thereby resolve()ing each project right before building it.
        val log = buildData.log
        log.info("Resolving: " + build.config.uri + " in directory: " + dir)
        val config2 = extractor.resolver.resolve(build.config, dir, log)
        if (config2 != build.config)
          sys.error("Internal error: during build, resolve changed the configuration. Please report. Before: " +
                    build.config + ", after: " + config2)
        extractor.resolver.prepare(build.config, dir, log)
        val (dependencies, version, writeRepo) = LocalBuildRunner.prepareDepsArtifacts(repository, build, outProjects, dir, log, buildData.debug)
        log.debug("Running local build: " + build.config + " in directory: " + dir)
        LocalRepoHelper.publishProjectInfo(build, repository, log)
        if (build.depInfo.isEmpty) sys.error("Internal error: depInfo is empty!")
        val results = builder.runBuild(build, tracker, dir,
          BuildInput(dependencies, version, build.configAndExtracted.extracted.projInfo.map{_.subproj}, writeRepo, build.config.name), this, buildData)
        LocalRepoHelper.publishArtifactsInfo(build, results, writeRepo, repository, log)
        if (exp.success < 0) IO.delete(dir.*(toFF("*")).get)
        markSuccess(dir)
        results
      } catch {
        case t: Throwable =>
          if (exp.failure < 0) IO.delete(dir.*(toFF("*")).get)
          throw t
      }
    }
}
object LocalBuildRunner {
  // Also used by the implementation of "dbuild checkout", in Checkout.scala
  // Rematerializes the project's dependencies, and prepares some other build information,
  // prior to the actual build.
  // Returns: (dependencies, version, writeRepo)
  def prepareDepsArtifacts(repository: Repository, build: RepeatableProjectBuild, outProjects: Seq[Project], dir: File, log: Logger, debug: Boolean): (BuildArtifactsInMulti, String, File) = {
    //
    val readRepos = localRepos(dir)
    val uuidGroups = build.depInfo map (_.dependencyUUIDs)
    val fromSpaces = build.configAndExtracted.getSpace.fromStream // one per uuidGroup
    val dependencies = LocalRepoHelper.getArtifactsFromUUIDs(log.info, repository, readRepos, uuidGroups, fromSpaces, debug)
    val BuildArtifactsInMulti(artifactLocations) = dependencies
    // Special case: scala-compiler etc must have the same version number
    // as scala-library: projects that rely on scala-compiler as a dependency
    // (notably sbt) may need that.
    // Therefore, if we see in the list of artifactLocations a scala-library,
    // and we are compiling another library of the Scala distribution, we check
    // that the scala-library version matches the current one.
    // Technically, we can even borrow the scala-library version, since it depends
    // on the dependency embedded within the RepeatableProjectBuild. Therefore the build
    // remains strictly repeatable, even if the library version does not appear in the
    // original project configuration: if anything changes in the scala-library dependency,
    // this project will also get a new uuid.
    // TODO: can we work around this quirk in a cleaner manner?
    log.debug(build.toString)
    // inspect only the artifacts reloaded at the base level
    val baseArtifacts = (artifactLocations.headOption getOrElse sys.error("Internal error: zero artifacts levels.")).artifacts
    val scalaLib = baseArtifacts find { a =>
      a.info.organization == "org.scala-lang" && a.info.name == "scala-library"
    }
    val libVersion = scalaLib flatMap { lib =>
      if (outProjects exists { p: Project =>
        p.organization == "org.scala-lang" && p.name.startsWith("scala")
      }) Some(lib.version) else None
    }
    val version = build.config.setVersion match {
      // calculate some (hopefully unique) default version
      case Some(v) => v
      case _ => {
        val value = build.depInfo.head.baseVersion // We only collect the artifacts from the base level, hence the "head"
        val Split = """^([\.0-9]+)(.*)$""".r
        // we strip away the original suffix, if any
        val Split(originalVersion, originalSuffix) = value
        val defaultVersion = originalVersion +
          (build.config.setVersionSuffix match {
            case None => "-" + ("dbuildx" + build.uuid)
            case Some("") =>
            case Some(suffix) if suffix.startsWith("%commit%") =>
              val len = try { suffix.drop(8).toInt } catch { case e: NumberFormatException => 99999 }
              // note: the suffix must contain at least one letter! numeric-only suffixes will be
              // understood by Maven as some variation over snapshots, leading to unexpected results
              val commit = build.getCommit getOrElse sys.error("This project is unable to provide a commit string, cannot use set-version-suffix: " + build.config.name)
              "-R" + commit.take(len)
            case Some(suffix) => {
              val numbers = ('0' to '9').toSet
              if (suffix.forall(numbers))
                log.warn("*** WARNING: an all-numeric suffix may be interpreted by Maven as a snapshot version; this is probably not what you want (suffix: \"" + suffix + "\"")
              "-" + suffix
            }
          })
        libVersion getOrElse defaultVersion
      }
    }
    // did we set libVersion in order to mark that this project should have a specific version (the one of scala-library),
    // and the current version is not what libVersion is? Emit a warning
    libVersion foreach { lv =>
      if (lv != version) {
        log.warn("*** Warning: project " + build.config.name + " generates a jar of the Scala distribution; its version has been set to")
        log.warn("*** " + version + ", but the version of scala-library is " + lv + ".")
        log.warn("*** The mismatch may cause a scala library not to be found. Please either omit this set-version, or use the same value")
        log.warn("*** in the set-version of both projects.")
      }
    }
    val writeRepo = publishRepo(dir)
    if (!writeRepo.exists()) writeRepo.mkdirs()
    (dependencies, version, writeRepo)
  }
  
}
