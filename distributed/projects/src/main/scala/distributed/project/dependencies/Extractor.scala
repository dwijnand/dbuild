package distributed
package project
package dependencies

import sbt.IO
import sbt.Path._
import java.io.File

import project.resolve.ProjectResolver
import model.{ProjectConfigAndExtracted,ProjectBuildConfig,ExtractedBuildMeta}
import logging._
import repo.core.Repository
import distributed.project.model.Utils.{writeValue,readValue}


/** This is used to extract dependencies from projects. */
class Extractor(
    resolver: ProjectResolver, 
    dependencyExtractor: BuildDependencyExtractor,
    repository: Repository) {
  
  /** Given an initial build configuration, extract *ALL* information needed for a full build. */
  def extract(tdir: File, build: ProjectBuildConfig, logger: logging.Logger): ProjectConfigAndExtracted = 
    distributed.repo.core.ProjectDirs.useProjectExtractionDirectory(build, tdir) { dir =>
      logger.debug("Resolving " + build.name + " in " + dir.getAbsolutePath)
      // we also remove the notification section, as it has no effect on builds or their repeatability
      val config = resolver.resolve(build, dir, logger).copy(notifications=Seq.empty)
      logger.debug("Repeatable Config: " + writeValue(config))
      // Here, we attempt to cache our extracted dependencies rather than do
      // resolution again.
      // TODO - This should be configurable!
      cachedExtractOr(config, logger) {
        logger.info("Extracting dependencies for: " + build.name)
        val deps = dependencyExtractor.extract(build, dir, logger)      
        logger.debug("Dependencies = " + writeValue(deps))
        cacheExtract(config, deps)
        ProjectConfigAndExtracted(config, deps)
      }
    }
  
    private def makeExtractKey(config: ProjectBuildConfig) = 
      "meta/extract/" + config.uuid
  
    private def cacheExtract(config: ProjectBuildConfig, extract: ExtractedBuildMeta): Unit =
      IO.withTemporaryFile("extract", config.uuid) { file =>
        IO.write(file, writeValue(extract))
        println("Putting extraction information into: " + repository)
        repository.put(makeExtractKey(config), file)
      }

    private def cachedExtractOr(config: ProjectBuildConfig, logger: logging.Logger)(f: => ProjectConfigAndExtracted): ProjectConfigAndExtracted = 
      try {
        val key = makeExtractKey(config)
    	val file = repository.get(key)
    	logger.debug("Dependencies are cached!")
    	val deps = readValue[ExtractedBuildMeta](file)
    	logger.debug("Dependencies = " + writeValue(deps))
    	if (deps.subproj.nonEmpty)
    	  logger.info(deps.subproj.mkString("The following subprojects will be built in project "+config.name+": ",", ",""))
    	ProjectConfigAndExtracted(config, deps)
      } catch {
        case _: Exception => f
      }
}
