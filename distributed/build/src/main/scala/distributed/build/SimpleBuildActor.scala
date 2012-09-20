package distributed
package build

import project.model._
import project.build._
import project.dependencies.ExtractBuildDependencies
import logging.Logger
import akka.actor.{Actor,ActorRef,Props}
import akka.pattern.{ask,pipe}
import akka.dispatch.{Future,Futures}
import akka.util.duration._
import akka.util.Timeout
import actorpaterns.forwardingErrorsToFutures
import sbt.Path._
import java.io.File

case class RunDistributedBuild(build: DistributedBuildConfig, target: File, logger: Logger)

// Very simple build actor that isn't smart about building and only works locally.
class SimpleBuildActor(extractor: ActorRef, builder: ActorRef) extends Actor {
  def receive = {
    case RunDistributedBuild(build, target, log) => forwardingErrorsToFutures(sender) {
      val listener = sender
      val logger = log.newNestedLogger(hashing.sha1Sum(build))
      val result = for {
        fullBuild <- analyze(build, target, log.newNestedLogger(hashing.sha1Sum(build)))
        fullLogger = log.newNestedLogger(hashing.sha1Sum(fullBuild))
        _ = fullLogger.info("---==   Repeatable Build Config   ===---")
        _ = fullLogger.info(config makeConfigString fullBuild.config)
        _ = fullLogger.info("---== End Repeatable Build Config ===---")
        arts <- runBuild(target, fullBuild, fullLogger)
        //_ = logPoms(fullBuild, arts, fullLogger)
      } yield arts
      result pipeTo listener
    }
  }
  
  def logPoms(build: RepeatableDistributedBuild, arts: BuildArtifacts, log: Logger): Unit = 
    try {
      log info "Printing Poms!"
      val poms = repo.PomHelper.makePomStrings(build, arts)
      log info (poms mkString "----------")
    } catch {
      case e: Throwable => 
        log trace e
        throw e
    }
  
  implicit val buildTimeout: Timeout = 4 hours 

  // Chain together some Asynch to run this build.
  def runBuild(target: File, build: RepeatableDistributedBuild, log: Logger): Future[BuildArtifacts] = {
    implicit val ctx = context.system
    val repeatable = build.config
    val tdir = local.ProjectDirs.makeDirForBuild(build.config, target / "build")
    def runBuild(builds: List[RepeatableProjectBuild], fArts: Future[BuildArtifacts]): Future[BuildArtifacts] = 
      builds match {
        case b :: rest =>
          val nextArts = for {
            arts <- fArts
            newArts <- buildProject(tdir, b, arts, log.newNestedLogger(b.config.name))
          } yield BuildArtifacts(arts.artifacts ++ newArts.artifacts, arts.localRepo)
          runBuild(rest, nextArts)
        case _ => fArts
      }
    local.ProjectDirs.userRepoDirFor(repeatable) { localRepo =>      
      runBuild(build.orderedBuilds.toList, Future(BuildArtifacts(Seq.empty, localRepo)))
    }
  }  
  
  // Asynchronously extract information from builds.
  def analyze(config: DistributedBuildConfig, target: File, log: Logger): Future[RepeatableDistributedBuild] = {
    implicit val ctx = context.system
    val tdir = local.ProjectDirs.makeDirForBuild(config, target / "extraction")
    val builds: Future[Seq[RepeatableProjectBuild]] = 
      Future.traverse(config.projects)(extract(tdir, log))
    // We don't have to do ordering here anymore.
    builds map RepeatableDistributedBuild.apply 
  } 
  
  // Our Asynchronous API.
  def extract(target: File, logger: Logger)(config: ProjectBuildConfig): Future[RepeatableProjectBuild] =
    (extractor ? ExtractBuildDependencies(config, target, logger.newNestedLogger(config.name))).mapTo[RepeatableProjectBuild]
  def buildProject(target: File, build: RepeatableProjectBuild, deps: BuildArtifacts, logger: Logger): Future[BuildArtifacts] =
    (builder ? RunBuild(target, build, deps, logger)).mapTo[BuildArtifacts]
}