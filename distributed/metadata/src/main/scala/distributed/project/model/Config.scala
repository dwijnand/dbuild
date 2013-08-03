package distributed.project.model

import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.lambdaworks.jacks._
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ser.std.StringSerializer

/**
 * Metadata about a build.  This is extracted from a config file and contains enough information
 * to further extract information about a build.
 */
@JsonDeserialize(using = classOf[BuildConfigDeserializer])
case class ProjectBuildConfig(name: String,
  system: String = "sbt",
  uri: String,
  @JsonProperty("set-version") setVersion: Option[String],
  notifications: Seq[Notification] = Seq.empty,
  extra: Option[ExtraConfig]) {
  def uuid = hashing sha1 this
}

case class ProjectBuildConfigShadow(name: String,
  system: String = "sbt",
  uri: String,
  @JsonProperty("set-version") setVersion: Option[String],
  notifications: Seq[Notification] = Seq.empty,
  extra: JsonNode = null)

/** The initial configuration for a build. Note that the global notification
 *  options are not included in the repeatable config, as notifications have
 *  no effect on the actual build, or its repeatability */
case class DistributedBuildConfig(projects: Seq[ProjectBuildConfig],
  deploy: Option[Seq[DeployOptions]],
  @JsonProperty("build-options") buildOptions: Option[GlobalBuildOptions],
  @JsonProperty("notification-options") notificationOptions: NotificationOptions=NotificationOptions())

/** Deploy information. */
case class DeployOptions(uri: String, // deploy target
  credentials: Option[String], // path to the credentials file
  projects: Option[Seq[DeployElement]], // names of the projects that should be deployed
  sign: Option[DeploySignOptions] // signing options
  )
case class DeploySubProjects(from: String, publish: Seq[String])

/**
 * Signing options.
 *  secret-ring is the path to the file containing the pgp secret key ring. If not supplied, '~/.gnupg/secring.gpg' is used.
 *  id is the long key id (the whole 64 bits). If not supplied, the default master key is used.
 *  passphrase is the path to the file containing the passphrase; there is no interactive option.
 */
case class DeploySignOptions(
  @JsonProperty("secret-ring") secretRing: Option[String],
  id: Option[String],
  passphrase: String)

/**
 * Configuration used for SBT and other builds.
 */
@JsonSerialize(using = classOf[ExtraSerializer])
class ExtraConfig

class ExtraSerializer extends JsonSerializer[ExtraConfig] {
  override def serialize(value: ExtraConfig, g: JsonGenerator, p: SerializerProvider) {
    val cfg = p.getConfig()
    val tf = cfg.getTypeFactory()
    val jt = tf.constructType(value.getClass)
    ScalaTypeSig(cfg.getTypeFactory, jt) match {
      case Some(sts) if sts.isCaseClass =>
        // the "true" below is for options.caseClassSkipNulls
        (new CaseClassSerializer(jt, sts.annotatedAccessors, true)).serialize(value.asInstanceOf[Product], g, p)
      case _ => throw new Exception("Internal error while serializing build system config. Please report.")
    }
  }
}

class BuildConfigDeserializer extends JsonDeserializer[ProjectBuildConfig] {
  override def deserialize(p: JsonParser, ctx: DeserializationContext): ProjectBuildConfig = {
    val buildSystems = BuildSystemExtras.buildSystems

    val tf = ctx.getConfig.getTypeFactory()
    val d = ctx.findContextualValueDeserializer(tf.constructType(classOf[ProjectBuildConfigShadow]), null)
    val generic = d.deserialize(p, ctx).asInstanceOf[ProjectBuildConfigShadow]

    if (generic == null) throw new Exception("Cannot deserialize build configuration: no value found")

    val from = generic.extra
    val system = generic.system
    if (!(buildSystems.contains(system))) throw new Exception("Build system \"" + system + "\" is unknown.")
    val newData = if (from == null) None else Some({
      val cls = buildSystems(system)
      val jp = from.traverse()
      jp.nextToken()
      cls.cast(ctx.findContextualValueDeserializer(tf.constructType(cls), null).deserialize(jp, ctx))
    })
    ProjectBuildConfig(generic.name, system, generic.uri, generic.setVersion, generic.notifications, newData)
  }
}
/**
 * The 'extra' options for the Scala build system are:
 * build-number:  Overwrites the standard build.number, with a custom number
 *                Note that set-version changes the jar artifact version number,
 *                while build-number changes the version that Scala reports, for
 *                example, in the REPL.
 * build-target:  Overrides the standard ant target that is invoked in order to
 *                generate the artifacts. The default is 'distpack-maven-opt', and it
 *                is not normally changed.
 * build-options: A sequence of additional options that will be passed to ant.
 *                They can specify properties, or modify in some other way the
 *                build. These options will be passed after the ones set by
 *                dbuild, on the command line.
 * All fields are optional.
 */
case class ScalaExtraConfig(
  @JsonProperty("build-number") buildNumber: Option[BuildNumber],
  @JsonProperty("build-target") buildTarget: Option[String],
  @JsonProperty("build-options") buildOptions: Seq[String] = Seq.empty,
  exclude: Seq[String] = Seq.empty // if empty -> exclude no projects (default)
  ) extends ExtraConfig

case class BuildNumber(major: String, minor: String, patch: String, bnum: String)

case class IvyExtraConfig(
  sources: Boolean = false,
  javadoc: Boolean = false,
  @JsonProperty("main-jar") mainJar: Boolean = true,
  artifacts: Seq[IvyArtifact] = Seq.empty,
  // The snapshot marker is used internally by the Ivy build system
  // in order to distinguish among different snapshots of the same
  // dependency, in which case it contains the publication date.
  // Note: this field is not for use by end user.
  @JsonProperty("snapshot-marker") snapshotMarker: Option[String]) extends ExtraConfig

case class IvyArtifact(
  classifier: String = "",
  @JsonProperty("type") typ: String = "jar",
  ext: String = "jar",
  configs: Seq[String] = Seq("default"))

case class MavenExtraConfig(
  directory: String = "") extends ExtraConfig

/**
 * sbt-specific build parameters
 */
case class SbtExtraConfig(
  @JsonProperty("sbt-version") sbtVersion: String = "", // Note: empty version is interpreted as default, when the Build System extracts this bit
  directory: String = "",
  @JsonProperty("measure-performance") measurePerformance: Boolean = false,
  @JsonProperty("run-tests") runTests: Boolean = true,
  options: Seq[String] = Seq.empty,
  // before extraction or building, run these commands ("set" or others)
  commands: Seq[String] = Seq.empty,
  projects: Seq[String] = Seq.empty, // if empty -> build all projects (default)
  exclude: Seq[String] = Seq.empty // if empty -> exclude no projects (default)
  ) extends ExtraConfig

object BuildSystemExtras {
  val buildSystems: Map[String, java.lang.Class[_ <: ExtraConfig]] = Map(
    "sbt" -> classOf[SbtExtraConfig],
    "scala" -> classOf[ScalaExtraConfig],
    "ivy" -> classOf[IvyExtraConfig],
    "maven" -> classOf[MavenExtraConfig])
}

// our simplified version of Either: we use it to group String and DeploySubProjects in a transparent manner
@JsonSerialize(using = classOf[DeployElementSerializer])
@JsonDeserialize(using = classOf[DeployElementDeserializer])
sealed abstract class DeployElement { def name: String }
case class DeployElementProject(a: String) extends DeployElement {
  override def toString() = a
  def name = a
}
case class DeployElementSubProject(info: DeploySubProjects) extends DeployElement {
  override def toString() = info.from + " " + info.publish.mkString("(", ",", ")")
  def name = info.from
}

class DeployElementSerializer extends JsonSerializer[DeployElement] {
  override def serialize(value: DeployElement, g: JsonGenerator, p: SerializerProvider) {
    value match {
      case DeployElementProject(s) =>
        new StringSerializer().serialize(s, g, p)
      case DeployElementSubProject(d) =>
        val cfg = p.getConfig()
        val tf = cfg.getTypeFactory()
        val jt = tf.constructType(classOf[DeploySubProjects])
        val Some(sts) = ScalaTypeSig(cfg.getTypeFactory, jt)
        // the "true" below is for options.caseClassSkipNulls
        (new CaseClassSerializer(jt, sts.annotatedAccessors, true)).serialize(d, g, p)
      case _ => throw new Exception("Internal error while serializing deploy projects. Please report.")
    }
  }
}
class DeployElementDeserializer extends JsonDeserializer[DeployElement] {
  override def deserialize(p: JsonParser, ctx: DeserializationContext): DeployElement = {
    val tf = ctx.getConfig.getTypeFactory()
    val d = ctx.findContextualValueDeserializer(tf.constructType(classOf[JsonNode]), null)
    val generic = d.deserialize(p, ctx).asInstanceOf[JsonNode]
    val jp = generic.traverse()
    jp.nextToken()
    def valueAs[T](cls: Class[T]) = cls.cast(ctx.findContextualValueDeserializer(tf.constructType(cls), null).deserialize(jp, ctx))
    if (generic.isTextual()) {
      DeployElementProject(valueAs(classOf[String]))
    } else {
      DeployElementSubProject(valueAs(classOf[DeploySubProjects]))
    }
  }
}

/**
 * These are options that affect all of the projects, but must not affect extraction:
 * extraction fully relies on the fact that the project is fully described by the
 * ProjectBuildConfig record.
 * Conversely, these options can affect the building stage; a copy of the record is
 * included in the RepeatableDistributedBuild, and is then included in each RepeatableProjectBuild
 * obtained from the repeatableBuilds within the RepeatableDistributedBuild.
 *
 * - cross-version controls the crossVersion and scalaBinaryVersion sbt flags. It can have the following values:
 *   - "disabled" (default): All cross-version suffixes will be disabled, and each project
 *     will be published with just a dbuild-specific version suffix (unless "set-version" is used).
 *     However, the library dependencies that refer to Scala projects that are not included in this build
 *     configuration, and that have "binary" or "full" CrossVersion will have their scala version set to
 *     the full scala version string: as a result, missing dependent projects will be detected.
 *   - "standard": Each project will compile with its own suffix (typically _2.10 for 2.10.x, for example).
 *     Further, library dependencies that refer to Scala projects that are not included in this build
 *     configuration will not be rewritten: they might end up being fetched from Maven if a compatible
 *     version is found.
 *     This settings must be used when releasing, typically in conjunction with "set-version", in order
 *     to make sure cross-versioning works as it would in the original projects.
 *   - "full": Similar in concept to "disabled", except the all the sbt projects are changed so that
 *     the full Scala version string is used as a cross-version suffix (even those that would normally
 *     have cross-version disabled). Missing dependent projects will be detected.
 *   - "binaryFull": It is a bit of a hybrid between standard and full. This option will cause
 *     the projects that would normally publish with a binary suffix (like "_2.10") to publish using the
 *     full scala version string instead. The projects that have cross building disabled, however, will be
 *     unaffected. Missing dependent projects will be detected. This configuration is for testing only.
 *
 * In practice, do not include a "build-options" section at all in normal use, and just add "{cross-version:standard}"
 * if you are planning to release using "set-version".
 */
case class GlobalBuildOptions(
  @JsonProperty("cross-version") crossVersion: String = "disabled")
  

/**
 * This section is used to notify users, by using some notification system.
 */
case class NotificationOptions(
  templates: Seq[NotificationTemplate] = Seq.empty,
  notifications: Seq[Notification] = Seq(Notification("console",ConsoleNotification()))
)
/** 
 *  A notification template; for notification systems that require short messages,
 *  use only the subject line. It is a template because variable
 *  substitution may occur before printing.
 *  It can have three components:
 *  1) A short summary (<50 characters), with a short message of what went wrong.
 *     It is required, and is suitable, for instance, for a short console report
 *     or as an email subject line.
 *  2) A slightly longer summary (<110 characters), suitable for SMS, Tweets, etc.
 *     It should be self-contained in terms of information. Defaults to the short summary.
 *  3) A long body with a more complete description. Defaults to an empty string. 
 *  An Id is also present, and is used to match against the (optional) template
 *  requested in the notification. 
 */
case class NotificationTemplate(
    id: String,
    summary:String,
    short:Option[String] = None,
    long:String = ""
)
case class Notification(
    kind:String,
    notification:NotificationKind,
    /** if None, default to the one from the outcome */
    template: Option[String] = None,
    /** sequence of BuildOutcome IDs */
    when:Seq[String]=Seq.empty
    ) {
  /**
   * It calls template(), if None, then get
   * the default template from the outcome, else
   * search in the list of defined templates (in NotificationOptions)
   * for one matching the one requested; if no short, replace with summary.
   * Return the result.
   * definedTemplates are those the user wrote in the configuration file
   */
  def resolveTemplate(outcome: BuildOutcome, definedTemplates: Seq[NotificationTemplate]): NotificationTemplate = {
    val templ = template match {
      case None => outcome.defaultTemplate
      case Some(t) => definedTemplates.find(_.id == t) getOrElse sys.error("The requested notification template \""+t+"\" was not found.")
    }
    templ.short match {
      case Some(_) => templ
      case None => templ.copy(short=Some(templ.summary))
    }
  }

}
abstract class NotificationKind

/**
 * We could embed send() into NotificationKind, but:
 * 1) too much logic would end up in the low-level metadata package
 * 2) we would not have access to higher-level context
 * Therefore the actual send() is implemented in a twin class higher up in dbuild.
 * We also offer an opportunity to the NotificationKinds to do something once before all the
 * notifications are sent, and something afterward. Only the notification kinds that
 * are actually used in the configuration file will be called.
 */
abstract class NotificationContext[T <: NotificationKind](implicit m: Manifest[T]) {
  def before() = {}
  def after() = {}
  /**
   * Send the notification using the template templ (do not use the one from outcome when implementing).
   *  If the notification fails, return Some(errorMessage).
   */
  def send(n: T, templ: NotificationTemplate, outcome: BuildOutcome): Option[String]

  // service code: the client calls the version below, which redispatches to send(), which is implemented in subclasses.
  // unfortunately, NotificationKinds are referred to by String IDs, so we can't be totally type safe
  def notify(n: NotificationKind, templ: NotificationTemplate, outcome: BuildOutcome): Option[String] = {
    // I have to make it work on 2.9 as well
    if (m.erasure.isInstance(n)) send(n.asInstanceOf[T], templ, outcome) else
      sys.error("Internal error: " + this.getClass.getName + " received a " + n.getClass.getName + ". Please report.")
  }
}

case class EmailNotification(
    to: Seq[String] = Seq.empty,
    cc: Seq[String] = Seq.empty,
    bcc: Seq[String] = Seq.empty
) extends NotificationKind

case class ConsoleNotification(
) extends NotificationKind

object NotificationKind {
  val kinds: Map[String, java.lang.Class[_ <: NotificationKind]] = Map(
    "console" -> classOf[ConsoleNotification],
    "email" -> classOf[EmailNotification])
}
