/*
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package com.typesafe.play.prune

import java.io._
import java.nio.file._
import java.util.{ List => JList, Map => JMap, UUID }
import java.util.concurrent.TimeUnit
import org.apache.commons.io.{ FileUtils, IOUtils }
import org.apache.commons.exec._
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib._
import org.eclipse.jgit.revwalk._
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.joda.time._
import scala.collection.JavaConversions
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

import Exec._
import PruneGit._

object BuildPlay {
  def buildPlay(playBranch: String, playCommit: String)(implicit ctx: Context): (UUID, PlayBuildRecord) = {

    val description = s"${playCommit.substring(0, 7)} [$playBranch]"

    val javaVersionExecution: Execution = JavaVersion.captureJavaVersion()

    def newPlayBuild(): (UUID, PlayBuildRecord) = {
      val newPlayBuildId = UUID.randomUUID()
      println(s"Starting new Play $description build: $newPlayBuildId")

      gitCheckout(
        localDir = ctx.playHome,
        branch = playBranch,
        commit = playCommit)

      val executions: Seq[Execution] = buildPlayDirectly(errorOnNonZeroExit = false)
      val newPlayBuildRecord = PlayBuildRecord(
        pruneInstanceId = ctx.pruneInstanceId,
        playCommit = playCommit,
        javaVersionExecution = javaVersionExecution,
        buildExecutions = executions
      )
      PlayBuildRecord.write(newPlayBuildId, newPlayBuildRecord)
      val oldPersistentState: PrunePersistentState = PrunePersistentState.readOrElse
      PrunePersistentState.write(oldPersistentState.copy(lastPlayBuild = Some(newPlayBuildId)))
      (newPlayBuildId, newPlayBuildRecord)
    }

    val o: Option[(UUID, PlayBuildRecord)] = for {
      persistentState <- PrunePersistentState.read
      lastPlayBuildId <- persistentState.lastPlayBuild
      lastPlayBuildRecord <- PlayBuildRecord.read(lastPlayBuildId)
    } yield {
      val reasonsToBuild: Seq[String] = {
        val differentCommit = if (lastPlayBuildRecord.playCommit == playCommit) Seq() else Seq(s"Play commit has changed to ${playCommit.substring(0,7)}")
        val differentJavaVersion = if (lastPlayBuildRecord.javaVersionExecution.stderr == javaVersionExecution.stderr) Seq() else Seq("Java version has changed")
        val emptyIvyDirectory = if (Files.exists(localIvyRepository)) Seq() else Seq("Local Ivy repository is missing")
        // TODO: Check previous build commands are OK
        differentCommit ++ differentJavaVersion ++ emptyIvyDirectory
      }
      if (reasonsToBuild.isEmpty) {
        println(s"Play $description already built: ${lastPlayBuildId}")
        (lastPlayBuildId, lastPlayBuildRecord)
      } else {
        println("Can't use existing Play build: " + (reasonsToBuild.mkString(", ")))
        newPlayBuild()
      }
    }
    o.getOrElse {
      println("No existing build record for Play")
      newPlayBuild()
    }
  }

  private def localIvyRepository(implicit ctx: Context): Path = {
    val ivyHome: String = ctx.config.getString("ivy.home")
    Paths.get(ivyHome).resolve("local")
  }

  def buildPlayDirectly(errorOnNonZeroExit: Boolean = true)(implicit ctx: Context): Seq[Execution] = {

    // While we're building there won't be a current Play build for this app
    val oldPersistentState: PrunePersistentState = PrunePersistentState.readOrElse
    PrunePersistentState.write(oldPersistentState.copy(lastPlayBuild = None))

    // Clear target directories and local Ivy repository to ensure an isolated build
    Seq(
      localIvyRepository,
      Paths.get(ctx.playHome, "framework/target"),
      Paths.get(ctx.playHome, "framework/project/target")
    ) foreach { p =>
      if (Files.exists(p)) {
        FileUtils.deleteDirectory(p.toFile)
      }
    }

    val buildCommands: Seq[Command] = Seq(
      Command(
        program = "./build",
        args = Seq("-Dsbt.ivy.home=<ivy.home>", "publish-local"),
        env = Map(
          "JAVA_HOME" -> "<java8.home>",
          "LANG" -> "en_US.UTF-8"
        ),
        workingDir = "<play.home>/framework"
      )
    )

    buildCommands.map(run(_, Pump, errorOnNonZeroExit = errorOnNonZeroExit))
  }

}