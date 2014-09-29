/*
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package com.typesafe.play.prune

import java.nio.file._
import java.util.UUID
import org.joda.time._
import play.api.libs.functional.syntax._
import play.api.libs.json._

object Records {

  def readFile[T](p: Path)(implicit reads: Reads[T]): Option[T] = {
    if (Files.exists(p)) {
      val bytes = Files.readAllBytes(p)
      val json = Json.parse(bytes)
      val t = reads.reads(json).get
      //println("Read: "+new String(bytes)+"->"+t)
      Some(t)
    } else None
  }

  def writeFile[T](p: Path, t: T)(implicit writes: Writes[T]): Unit = {
    val json = writes.writes(t)
    val bytes = Json.prettyPrint(json).getBytes("UTF-8")
    //println("Write: "+t+"->"+new String(bytes))
    Files.createDirectories(p.getParent)
    Files.write(p, bytes)
  }

}

object Implicits {

  val DateFormat = "yyyy-MM-dd'T'HH:mm:ssz"

  implicit def dateReads = Reads.jodaDateReads(DateFormat)

  implicit def dateWrites = Writes.jodaDateWrites(DateFormat)

  implicit def uuidWrites = new Writes[UUID] {
    def writes(uuid: UUID) = JsString(uuid.toString)
  }
  implicit def uuidReads = new Reads[UUID] {
    def reads(json: JsValue): JsResult[UUID] = json match {
      case JsString(s) => try {
          JsSuccess(UUID.fromString(s))
        } catch {
          case _: IllegalArgumentException => JsError("Invalid UUID string")
        }
      case _ => JsError("UUIDs must be encoded as JSON strings")
    }
  }
}

case class PrunePersistentState(
  lastPlayBuild: Option[UUID],
  lastTestBuilds: Map[String,UUID]
)

object PrunePersistentState {

  implicit val writes = new Writes[PrunePersistentState] {
    def writes(prunePersistentState: PrunePersistentState) = Json.obj(
      "lastPlayBuild" -> prunePersistentState.lastPlayBuild,
      "lastTestBuilds" -> prunePersistentState.lastTestBuilds
    )
  }

  implicit val reads: Reads[PrunePersistentState] = (
    (JsPath \ "lastPlayBuild").readNullable[UUID] and
    (JsPath \ "lastTestBuilds").read[Map[String,UUID]]
  )(PrunePersistentState.apply _)

  def path(implicit ctx: Context): Path = {
    Paths.get(ctx.pruneHome).resolve("state.json")
  }

  def read(implicit ctx: Context): Option[PrunePersistentState] = {
    Records.readFile[PrunePersistentState](path)
  }

  def write(state: PrunePersistentState)(implicit ctx: Context): Unit = {
    Records.writeFile(path, state)
  }

}

object Command {

  implicit val writes = new Writes[Command] {
    def writes(command: Command) = Json.obj(
      "program" -> command.program,
      "args" -> command.args,
      "workingDir" -> command.workingDir,
      "env" -> command.env
    )
  }

  implicit val reads: Reads[Command] = (
    (JsPath \ "program").read[String] and
    (JsPath \ "args").read[Seq[String]] and
    (JsPath \ "workingDir").read[String] and
    (JsPath \ "env").read[Map[String, String]]
  )(Command.apply _)

}

case class Execution(
  command: Command,
  startTime: DateTime,
  endTime: DateTime,
  stdout: Option[String],
  stderr: Option[String],
  returnCode: Option[Int]
)


object Execution {

  implicit val writes = new Writes[Execution] {
    def writes(execution: Execution) = Json.obj(
      "command" -> execution.command,
      "startTime" -> execution.startTime,
      "endTime" -> execution.endTime,
      "stdout" -> execution.stdout,
      "stderr" -> execution.stderr,
      "returnCode" -> execution.returnCode
    )
  }

  implicit val reads: Reads[Execution] = (
    (JsPath \ "command").read[Command] and
    (JsPath \ "startTime").read[DateTime] and
    (JsPath \ "endTime").read[DateTime] and
    (JsPath \ "stdout").readNullable[String] and
    (JsPath \ "stderr").readNullable[String] and
    (JsPath \ "returnCode").readNullable[Int]
  )(Execution.apply _)

}

// {
//   "playCommit": "a1b2...",
//   "javaVersion": "java version \"1.8.0_05\"\nJava(TM) SE Runtime Environment (build 1.8.0_05-b13)Java HotSpot(TM) 64-Bit Server VM (build 25.5-b02, mixed mode)",
//   "buildCommands": [
//     ["./build", "-Dsbt.ivy.home=~/.prune/ivy", "clean"],
//     ["./build", "-Dsbt.ivy.home=~/.prune/ivy", "publish-local"],
//     ["./build", "-Dsbt.ivy.home=~/.prune/ivy", "-Dscala.version=2.11.2", "publish-local"]
//   ]
// }

case class PlayBuildRecord(
  pruneInstanceId: UUID,
  playCommit: String,
  javaVersionExecution: Execution,
  buildExecutions: Seq[Execution]
)

object PlayBuildRecord {

  implicit val writes = new Writes[PlayBuildRecord] {
    def writes(playBuildRecord: PlayBuildRecord) = Json.obj(
      "pruneInstanceId" -> playBuildRecord.pruneInstanceId,
      "playCommit" -> playBuildRecord.playCommit,
      "javaVersionExecution" -> playBuildRecord.javaVersionExecution,
      "buildExecutions" -> playBuildRecord.buildExecutions
    )
  }

  implicit val reads: Reads[PlayBuildRecord] = (
    (JsPath \ "pruneInstanceId").read[UUID] and
    (JsPath \ "playCommit").read[String] and
    (JsPath \ "javaVersionExecution").read[Execution] and
    (JsPath \ "buildExecutions").read[Seq[Execution]]
  )(PlayBuildRecord.apply _)

}

case class TestBuildRecord(
  pruneInstanceId: UUID,
  playBuildId: UUID,
  testProject: String,
  testsCommit: String,
  javaVersionExecution: Execution,
  buildExecutions: Seq[Execution]
)

object TestBuildRecord {

  implicit val writes = new Writes[TestBuildRecord] {
    def writes(testBuildRecord: TestBuildRecord) = Json.obj(
      "pruneInstanceId" -> testBuildRecord.pruneInstanceId,
      "playBuildId" -> testBuildRecord.playBuildId,
      "testProject" -> testBuildRecord.testProject,
      "testsCommit" -> testBuildRecord.testsCommit,
      "javaVersionExecution" -> testBuildRecord.javaVersionExecution,
      "buildExecutions" -> testBuildRecord.buildExecutions
    )
  }

  implicit val reads: Reads[TestBuildRecord] = (
    (JsPath \ "pruneInstanceId").read[UUID] and
    (JsPath \ "playBuildId").read[UUID] and
    (JsPath \ "testProject").read[String] and
    (JsPath \ "testsCommit").read[String] and
    (JsPath \ "javaVersionExecution").read[Execution] and
    (JsPath \ "buildExecutions").read[Seq[Execution]]
  )(TestBuildRecord.apply _)

}

case class Command(
  program: String,
  args: Seq[String] = Nil,
  workingDir: String,
  env: Map[String, String]
) {
  private def mapStrings(f: String => String): Command = {
    Command(
      program = f(program),
      args = args.map(f),
      workingDir = f(workingDir),
      env = env.mapValues(f)
    )
  }
  def replace(original: String, replacement: String): Command = {
    mapStrings(_.replace(original, replacement))
  }  
}

case class TestRunRecord(
  pruneInstanceId: UUID,
  testBuildId: UUID,
  testName: String,
  javaVersionExecution: Execution,
  serverExecution: Execution,
  wrkExecutions: Seq[Execution]
)

object TestRunRecord {

  implicit val writes = new Writes[TestRunRecord] {
    def writes(testBuildRecord: TestRunRecord) = Json.obj(
      "pruneInstanceId" -> testBuildRecord.pruneInstanceId,
      "testBuildId" -> testBuildRecord.testBuildId,
      "testName" -> testBuildRecord.testName,
      "javaVersionExecution" -> testBuildRecord.javaVersionExecution,
      "serverExecution" -> testBuildRecord.serverExecution,
      "wrkExecutions" -> testBuildRecord.wrkExecutions
    )
  }

  implicit val reads: Reads[TestRunRecord] = (
    (JsPath \ "pruneInstanceId").read[UUID] and
    (JsPath \ "testBuildId").read[UUID] and
    (JsPath \ "testName").read[String] and
    (JsPath \ "javaVersionExecution").read[Execution] and
    (JsPath \ "serverExecution").read[Execution] and
    (JsPath \ "wrkExecutions").read[Seq[Execution]]
  )(TestRunRecord.apply _)

}

