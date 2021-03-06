package com.typesafe.dbuild.plugin

import sbt._
import com.typesafe.dbuild.adapter.Adapter
import Adapter.syntaxio._
import Adapter.defaultID
import com.typesafe.dbuild.model
import com.typesafe.dbuild.plugin.StateHelpers._
import com.typesafe.dbuild.support.sbt.ExtractionInput
import com.typesafe.dbuild.support.NameFixer.fixName
import DBuildRunner.getSortedProjects
import com.typesafe.dbuild.model.Utils.{ writeValue, readValue }
import org.apache.commons.io.FileUtils.writeStringToFile

object DependencyAnalysis {
  // TODO - make a task that generates this metadata and just call it!

  /** Pulls the name/organization/version for each project in the build. */
  private def getProjectInfos(extracted: Extracted, state: State, refs: Iterable[ProjectRef]): Seq[model.Project] =
    (Vector[model.Project]() /: refs) { (dependencies, ref) =>
      val name = fixName(extracted.get(Keys.name in ref))
      val organization = extracted.get(Keys.organization in ref)

      // Project dependencies (TODO - Custom task for this...)
      val (_, pdeps) = extracted.runTask(Keys.projectDependencies in ref, state)
      val ldeps = extracted.get(Keys.libraryDependencies in ref)
      def artifactsNoEmpty(name: String, arts: Seq[Artifact]) =
        if (!arts.isEmpty) arts
        else Seq(Artifact(name))
      val deps = (for {
        d <- (pdeps ++ ldeps)
        a <- artifactsNoEmpty(d.name, d.explicitArtifacts)
      } yield model.ProjectRef(fixName(a.name), d.organization, a.extension, a.classifier)) :+
        // We need to add a fictitious, dbuild-only dependency, so that the compiler is compiled
        // first, and available to create a suitable scalaInstance
        model.ProjectRef("scala-compiler", "org.scala-lang", "jar", None)

      // Project Artifacts
      val skipPublish = extracted.runTask(Keys.skip in (ref, Keys.publish), state)._2
      val artifacts = if (skipPublish) Seq[model.ProjectRef]() else for {
        a <- extracted get (Keys.artifacts in ref)
      } yield model.ProjectRef(fixName(a.name), organization, a.extension, a.classifier)

      // Append ourselves to the list of projects...
      dependencies :+ model.Project(
        name,
        organization,
        artifacts,
        deps.distinct)
    }

  // TODO: move to a different file; the two routines below are used both by DependencyAnalysis and by DBuildRunner
  // Also move there verifySubProjects() and isValidProject().

  // Some additional hacks will be required, due to sbt's default projects. Such projects may take names like "default-b46525" (in 0.12 or 0.13),
  // or the name of the build directory (in 0.13). In case of plugins in 0.13, a default sbt project may have other names, but we are not concerned
  // with that at the moment.
  // Since dbuild uses different directories, the default project names may change along the way. In order to recognize them, we
  // need to discover them and normalize their names to something recognizable across extractions/builds.
  // In particular, with 0.13 the default project name may be the name of the build directory. Both extraction and build use, as their checkout
  // directory, a uuid. That will makes the default project name pretty unique and easy to recognize.
  //
  // Use these normalization routines only when printing or comparing
  //
  def normalizedProjectName(s: ProjectRef, baseDirectory: File) = normalizedProjectNameString(s.project, baseDirectory)
  def normalizedProjectNameString(name: String, baseDirectory: File): String = {
    // we only cover the most common cases. The full logic for 0.13 may involve Load.scala (autoID) and the def default* in Build.scala
    // This "toLowerCase" etc comes from the long deprecated "StringUtilities.normalize()"
    val base = baseDirectory.getName.toLowerCase(java.util.Locale.ENGLISH).replaceAll("""\W+""", "-")
    val defaultIDs = Seq(defaultID(baseDirectory), "root-" + base, base)
    val defaultName = "default-sbt-project"
    if (defaultIDs contains name) defaultName else {
      if (name.endsWith("-build") && base == "project" && normalizedProjectNameString(name.dropRight(6), baseDirectory.getParentFile) == defaultName)
        defaultName
      else
        name
    }
  }
  def normalizedProjectNames(r: Seq[ProjectRef], baseDirectory: File) = r map { p => normalizedProjectName(p, baseDirectory) }

  /** Do we need to use a specific Scala version during extraction? If so, set it now. */
  def fixExtractionScalaVersion2(opt: Option[String]): (Seq[Setting[_]], sbt.Logger) => Seq[Setting[_]] = opt match {
    case None => (a: Seq[Setting[_]], _) => a
    case Some(v) => DBuildRunner.fixGeneric2(Keys.scalaVersion, "Setting extraction Scala version to " + v) { _ => v }
  }

  /** Prints the dependencies to the given file. */
  def printDependencies(state: State, extractionInput: ExtractionInput, resultFile: File, log: Logger): State = {

    val ExtractionInput(projects, excludedProjects, extractionScalaVersion, debug) = extractionInput

    val extracted = Project.extract(state)
    import extracted._

    val Some(baseDirectory) = Keys.baseDirectory in ThisBuild get structure.data
    log.debug("Extracting dependencies: " + baseDirectory.getCanonicalPath)

    def normalizedProjectNames(r: Seq[ProjectRef]) = DependencyAnalysis.normalizedProjectNames(r, baseDirectory)
    def normalizedProjectName(p: ProjectRef) = DependencyAnalysis.normalizedProjectName(p, baseDirectory)

    val allRefs = getProjectRefs(extracted)

    // we rely on allRefs to not contain duplicates. Let's insert an additional sanity check, just in case
    val allRefsNames = normalizedProjectNames(allRefs)
    if (allRefsNames.distinct.size != allRefsNames.size)
      sys.error(allRefsNames.mkString("Unexpected internal error: found duplicate name in ProjectRefs. List is: ", ",", ""))

    // now let's get the list of projects excluded and requested, as specified in the dbuild configuration file
    // note that getSortedProjects() already calls verifySubProjects(), which checks all arguments for sanity, printing messages
    // if anything in the passed projects list is incorrect.
    val excluded = getSortedProjects(excludedProjects, allRefs, baseDirectory, acceptPatterns = true)

    val requested = {
      if (projects.isEmpty)
        allRefs.diff(excluded)
      else {
        val requestedPreExclusion = getSortedProjects(projects, allRefs, baseDirectory, acceptPatterns = true)
        if (requestedPreExclusion.intersect(excluded).nonEmpty) {
          log.warn(normalizedProjectNames(requestedPreExclusion.intersect(excluded))
            mkString ("*** Warning *** You are simultaneously requesting and excluding some subprojects; they will be excluded. They are: ", ",", ""))
        }
        requestedPreExclusion.diff(excluded)
      }
    }

    // this will be the list of ProjectRefs that will actually be built, in the right sequence
    val refs = {

      import com.typesafe.dbuild.graph._
      // we need to linearize the list of subprojects. If this is not done,
      // when we try to build one of the (sbt) subprojects, multiple ones can be
      // built at once, which prevents us from finding easily which files are
      // created by which subprojects.
      // further, we also ned to find the full set of subprojects that are dependencies
      // of the ones that are listed in the configuration file. That is necessary both
      // in order to build them in the correct order, as well as in order to find in turn
      // their external dependencies, which we need to know about.

      // I introduce a local implementation of graphs. Please bear with me for a moment.
      // I use SimpleNode[ProjectRef] & SimpleEdge[ProjectRef,DepType]
      sealed class DepType
      case object Dependency extends DepType
      case object Aggregate extends DepType
      class SubProjGraph(projs: Seq[ProjectRef], directDeps: Map[ProjectRef, Set[ProjectRef]],
        directAggregates: Map[ProjectRef, Set[ProjectRef]]) extends Graph[ProjectRef, DepType] {
        val nodeMap: Map[ProjectRef, Node[ProjectRef]] = (projs map { p => (p, SimpleNode(p)) }).toMap
        private def wrapEdges(kind: DepType, edges: Map[ProjectRef, Set[ProjectRef]]) = edges map {
          case (from, to) => (nodeMap(from), to map nodeMap map { SimpleEdge(nodeMap(from), _, kind) } toSeq)
        }
        val nodes: Set[Node[ProjectRef]] = nodeMap.values.toSet
        private val edgeMap: Map[Node[ProjectRef], Seq[Edge[ProjectRef, DepType]]] = {
          val w1 = wrapEdges(Dependency, directDeps)
          val w2 = wrapEdges(Aggregate, directAggregates)
          (nodes map { n => n -> (w1(n) ++ w2(n)) }).toMap
        }
        def edges(n: Node[ProjectRef]): Seq[Edge[ProjectRef, DepType]] = edgeMap(n)
      }

      // OK, now I need a topological ordering over all the defined projects, according to the inter-project
      // dependencies. I obtain the dependencies from "extracted", I build a graph, then I use the
      // topological sort facilities in the graph package. (NB: I could maybe recycle some equivalent sbt
      // facilities, that are certainly in there, somewhere)

      // I must consider in the ordering both the inter-project dependencies, as well as the aggregates.

      // utility map from the project name to its ProjectRef
      val allProjRefsMap = (allRefs map { r => (r.project, r) }).toMap

      // Now let's extract sbt inter-project dependencies (only the direct ones).
      // Note that the subprojects defined by dependsOn(uri) *do not* appear in
      // extracted.currentUnit.defined; we consequently scan extracted.structure.allProjects
      // instead (which in theory should contain the same information as allRefs,
      // aka extracted.structure.allProjectRefs).
      val allProjDeps = (extracted.structure.allProjects map { p =>
        (allProjRefsMap(p.id), (extracted.currentUnit.defined.get(p.id) map { rp =>
          rp.dependencies map { _.project } toSet
        }) getOrElse Set[ProjectRef]())
      }).toMap

      // The same, for the "aggregate" relationship (only direct ones, not the transitive set)
      val allProjAggregates = extracted.structure.allProjects map { p =>
        (allProjRefsMap(p.id), p.aggregate.toSet)
      } toMap

      // some debugging won't hurt
      log.info("Dependencies among subprojects:")
      def dumpDeps(deps: Map[ProjectRef, Set[ProjectRef]]) = {
        deps map {
          case (s, l) =>
            log.info(normalizedProjectName(s) + " -> " + normalizedProjectNames(l.toSeq).mkString(","))
        }
      }
      dumpDeps(allProjDeps)
      log.info("Aggregates of subprojects:")
      dumpDeps(allProjAggregates)
      log.info("Building graph...")
      val allProjGraph = new SubProjGraph(allRefs, allProjDeps, allProjAggregates)
      log.debug("The graph contains:")
      allProjGraph.nodes foreach { n =>
        log.debug("Node: " + normalizedProjectName(n.value))
        allProjGraph.edges(n) foreach { e => log.debug("edge: " + normalizedProjectName(n.value) + " to " + normalizedProjectName(e.to.value) + " (" + e.value + ")") }
      }

      // at this point I have my graph with all the relationships, and a list of "requested" projectRefs.
      // I need to find out 1) if there happen to be cycles (as a sanity check), and 2) the transitive set
      // of projects reachable from "requested", or rather the reachable subgraph.

      // 1) safeTopological() will check for cycles

      log.info("sorting...")
      val allProjSorted = allProjGraph.safeTopological
      log.debug(normalizedProjectNames(allRefs).mkString("original: ", ", ", ""))
      log.debug(normalizedProjectNames(allProjSorted.map { _.value }).mkString("sorted: ", ", ", ""))
      log.debug("dot: " + allProjGraph.toDotFile(normalizedProjectName))

      // Excellent. 2) Now we need the set of projects transitively reachable from "requested".

      // note that excluded subprojects are only excluded individually: no transitive analysis
      // is performed on exclusions.
      // (if we are building all subprojects, and there are no exclusions, skip this step)

      val needed = requested.foldLeft(Set[Node[ProjectRef]]()) { (set, node) =>
        set ++ allProjGraph.subGraphFrom(allProjGraph.nodeMap(node))
      } map { _.value }

      // In the end, our final sorted list (prior to explicit exclusions) is:
      // (keep the order of allProjSorted)
      val result = allProjSorted map { _.value } intersect needed.toSeq diff excluded

      // Have we introduced new subprojects? (likely). If so, warn the user.
      if (result.size != requested.size) {
        log.warn("*** Warning *** Some additional subprojects will be included, as they are needed by the requested subprojects.")
        log.warn(normalizedProjectNames(requested).mkString("Originally requested: ", ", ", ""))
        log.warn(normalizedProjectNames(result diff requested).mkString("Now added: ", ", ", ""))
      }

      // Have some of the needed subprojects been excluded? If so, print a warning,
      // but only if the user did specify an explicit list of requested subprojects.
      if (projects.nonEmpty && needed.intersect(excluded.toSet).nonEmpty) {
        log.warn("*** Warning *** Some subprojects are dependencies, but have been explicitly excluded.")
        log.warn("You may have to build them in a different project.")
        log.warn(normalizedProjectNames(needed.intersect(excluded.toSet).toSeq).mkString("Needed: ", ", ", ""))
      }

      result
    }.reverse // from the leaves to the roots

    if (refs.isEmpty)
      log.warn("*** Warning*** No subprojects will be compiled in this project")
    else
      log.info(normalizedProjectNames(refs).mkString("These subprojects will be built (in this order): ", ", ", ""))

    val deps = getProjectInfos(extracted, state, refs)

    // TODO: why only the root version? We might as well grab that of each subproject
    val Some(version) = Keys.version in currentRef get structure.data
    // return just this version string now; we will append to it more stuff prior to building

    val meta = model.ProjMeta(version, deps, normalizedProjectNames(refs)) // return the new list of subprojects as well!
    writeStringToFile(resultFile, writeValue(meta), /* VM default */ null: String)

    state
  }

  /** called by onLoad() during extraction */
  def printCmd(state: State, previousOnLoad: State => State): State = {
    import com.typesafe.dbuild.support.sbt.SbtRunner.SbtFileNames._

    val extracted = Project.extract(state)
    val Some(baseDirectory) = sbt.Keys.baseDirectory in ThisBuild get extracted.structure.data
    import Adapter.syntaxio._
    val dbuildDir = baseDirectory / dbuildSbtDirName
    val resultFile = dbuildDir / extractionOutputFileName
    val inputFile = dbuildDir / extractionInputFileName

    val Some(lastMsgFileName) = Option(System.getProperty("dbuild.sbt-runner.last-msg"))
    val lastMsgFile = new File(lastMsgFileName)

    val extractionInput = readValue[ExtractionInput](inputFile)

    val log = sbt.ConsoleLogger()
    if (extractionInput.debug) log.setLevel(Level.Debug)

    def prepareExtractionSettings(oldSettings: Seq[Setting[_]]) = {
      Seq[(Seq[Setting[_]], Logger) => Seq[Setting[_]]](
        fixExtractionScalaVersion2(extractionInput.extractionScalaVersion),
        DBuildRunner.restorePreviousOnLoad(previousOnLoad)) flatMap { _(oldSettings, log) }
    }

    saveLastMsg(lastMsgFile, printDependencies(_, extractionInput, resultFile,
      log))(DBuildRunner.newState(state, extracted, prepareExtractionSettings, log))
  }

}
