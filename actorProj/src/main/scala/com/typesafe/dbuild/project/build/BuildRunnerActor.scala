package com.typesafe.dbuild.project.build

import com.typesafe.dbuild.model._
import com.typesafe.dbuild.logging.Logger
import akka.actor.{ ActorRef, Actor, Props, PoisonPill, Terminated }
import akka.util.Timeout
import scala.concurrent.Await
import scala.concurrent.duration._
import com.typesafe.dbuild.project.resolve.ProjectResolver
import java.io.File
import com.typesafe.dbuild.repo.core._
import sbt.IO
import com.typesafe.dbuild.project.cleanup.Recycling._
import sbt.Path._
import com.typesafe.dbuild.repo.core.GlobalDirs.buildDir
import com.typesafe.dbuild.project.{ BuildSystem, BuildData }
import com.typesafe.dbuild.utils.TrackedProcessBuilder
import akka.pattern.ask
import java.util.concurrent.TimeoutException
import scala.util.{Success,Failure}
import Logger.prepareLogMsg

case class RunBuild(build: RepeatableProjectBuild, outProjects: Seq[Project], children: Seq[BuildOutcome], buildData: BuildData)

class CleaningBuildActor extends Actor {
  def receive = {
    case target: File =>
      IO.delete(buildDir(target).*(sbt.DirectoryFilter).get.filter(markedForDeletion))
      self ! PoisonPill
  }
}

/** This actor can run builds locally and return the generated artifacts. */
class BuildRunnerActor(builder: LocalBuildRunner, target: File, exp: CleanupExpirations,
                       tracker: TrackedProcessBuilder) extends Actor {
  override def preStart() = {
    // Cleanup works in two stages; see ExtractorActor for details.
    // Note that cleanup is performed independently for the extraction and build directories
    buildDir(target).*(sbt.DirectoryFilter).get.filter(upForDeletion(_, exp)).foreach(prepareForDeletion)
    // spawn the cleaning actor
    context.actorOf(Props(new CleaningBuildActor)) ! target
  }

  def receive = {
    case RunBuild(build, outProjects, children, buildData@BuildData(log, _)) =>
      log info ("--== Building %s ==--" format (build.config.name))
      sender ! (try {
        builder.checkCacheThenBuild(target, build, tracker, outProjects, children, buildData, exp)
      } catch {
        case t:Throwable =>
          BuildFailed(build.config.name, children, prepareLogMsg(log, t))
      })
      log info ("--== End Building %s ==--" format (build.config.name))
  }
}

class TimedBuildRunnerActor(builder: LocalBuildRunner, target: File,
        exp: CleanupExpirations, buildDuration: FiniteDuration) extends Actor {
  val tracker = new TrackedProcessBuilder
  val realBuilder = context.actorOf(Props(new BuildRunnerActor(builder, target, exp, tracker)))
  def receive = {
    case msg@RunBuild(build, outProjects, children, buildData@BuildData(log, _)) =>
      val originalSender = sender // need to copy, as we we'll use it later in a future (andThen)
      tracker.reset()
      val future1 = (realBuilder ? msg)(Timeout(buildDuration))
      val future2 = future1.andThen {
        case Success(answer) =>
          originalSender ! answer
        case Failure(e) =>
          tracker.abort()
          // Note that the Process may not be destroyed immediately, and may only stop
          // upon return from a system call, for example. We give it a little time here,
          // just in case, although the eventual dead letter response might arrive even later.
          Thread.sleep(5000)
          e match {
            case timeout: TimeoutException =>
              val timeoutMsg =
                "Timeout: build took longer than " + buildDuration
              log.error(timeoutMsg)
              originalSender ! new BuildFailed(build.config.name, children, timeoutMsg) with TimedOut
            case _ =>
              originalSender ! akka.actor.Status.Failure(e)
          }
      } (scala.concurrent.ExecutionContext.Implicits.global)

      // We only want one extraction operation at a time, in here. We use
      // the "Timed" builder, and the ask operation, mainly to avoid
      // creating a watchdog future, which will then keep running for
      // several hours without a chance to be interrupted (eating a thread)
      // So, yes: really wait. Will timeout after buildDuration, in case.
      //
      Await.ready(future2, Duration.Inf)
  }
}
