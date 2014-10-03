/*
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package com.typesafe.play.prune

import java.io._
import java.nio.file._
import java.util.{ List => JList }
import java.util.concurrent.TimeUnit
import org.apache.commons.io.{ FileUtils, IOUtils }
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib._
import org.eclipse.jgit.revwalk._
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.{RemoteConfig, RefSpec}
import scala.collection.JavaConversions
import scala.collection.convert.WrapAsJava._
import scala.collection.convert.WrapAsScala._

object PruneGit {

  def withRepository[T](localDir: String)(f: Repository => T): T = {
    val builder = new FileRepositoryBuilder()
    val repository = builder.setGitDir(Paths.get(localDir).resolve(".git").toFile)
            .readEnvironment() // Do we need this?
            .findGitDir()
            .build()
    val result = f(repository)
    repository.close()
    result
  }

  def resolveId(localDir: String, branch: String, rev: String): AnyObjectId = {
    withRepository(localDir) { repository =>
      val fixedRev = if (rev == "HEAD") s"refs/heads/$branch" else rev
      Option(repository.resolve(fixedRev)).getOrElse(sys.error(s"Couldn't resolve revision $rev on branch $branch in repo $localDir"))
    }
  }

  private def refSpec(branch: String): RefSpec = {
    new RefSpec(s"refs/heads/$branch:refs/remotes/origin/$branch")
  }

  def gitSync(
    remote: String,
    localDir: String,
    branches: Seq[String],
    checkedOutBranch: Option[String]): Unit = {

    val branchesString = branches.mkString("[", ", ", "]")
    val desc = s"$remote $branchesString into $localDir"

    val localDirPath = Paths.get(localDir)
    if (Files.notExists(localDirPath)) {
      println(s"Cloning $desc...")
      Files.createDirectories(localDirPath.getParent)
      Git.cloneRepository
        .setURI(remote)
        .setBranchesToClone(seqAsJavaList(branches))
        .setBranch(checkedOutBranch.getOrElse(branches.head))
        .setDirectory(localDirPath.toFile).call()
      println("Clone done.")
    } else {
      println(s"Fetching $desc...")
      withRepository(localDir) { repository =>
        validateRemoteOrigin(repository, remote)
        val refSpecs = branches.map(refSpec)
        new Git(repository)
          .fetch()
          .setRemote("origin")
          .setRefSpecs(refSpecs: _*)
          .call()
      }
      println("Fetch done")
    }

  }

  private def validateRemoteOrigin(repository: Repository, remote: String): Unit = {
    val config = repository.getConfig
    val remoteConfig = new RemoteConfig(config, "origin")
    val existingURIs = remoteConfig.getURIs
    assert(existingURIs.size == 1)
    val existingRemote = existingURIs.get(0).toString
    assert(existingRemote == remote, s"Remote URI for origin must be $remote, was $existingRemote")
  }

  def gitCheckout(localDir: String, branch: String, commit: String): Unit = {
    withRepository(localDir) { repository =>
      val result = new Git(repository).checkout().setName(branch).setStartPoint(commit).call()
    }
  }

  def gitPushChanges(
               remote: String,
               localDir: String,
               branch: String): Unit = {

    println(s"Pushing changes in $localDir to $remote branch $branch")

    withRepository(localDir) { repository =>
      val localGit: Git = new Git(repository)
      localGit.add.addFilepattern(".").call()
      //val result = localGit.push().
      val status = localGit.status.call()
      if (!status.getAdded.isEmpty) {
        localGit.commit.setAll(true).setMessage("Added records").call()
      }
      println(s"Pushing records to $remote [$branch]")
      val pushes = localGit.push.setRemote("origin").setRefSpecs(new RefSpec(s"$branch:$branch")).call()
      for {
        push <- iterableAsScalaIterable(pushes)
        remoteUpdate <- iterableAsScalaIterable(push.getRemoteUpdates)
      } {
        println(s"Pushed ${remoteUpdate.getSrcRef}: ${remoteUpdate.getStatus} ${remoteUpdate.getNewObjectId.name}")
      }
    }

  }

  case class LogEntry(id: String, parentCount: Int, shortMessage: String)

  def gitLog(localDir: String, branch: String, startRev: String, endRev: String): Seq[LogEntry] = {
    withRepository(localDir) { repository =>
      val startId = resolveId(localDir, branch, startRev)
      val endId = resolveId(localDir, branch, endRev)
      // println(s"Logging from $startId to $endId")
      val result = new Git(repository).log().addRange(startId, endId).call()
      val logEntries: Iterable[LogEntry] = iterableAsScalaIterable(result).map { revCommit =>
        //println(revCommit.getId.getName)
        LogEntry(revCommit.getId.name, revCommit.getParentCount, revCommit.getShortMessage)
      }
      logEntries.to[Seq]
    }
  }

  def gitFirstParentsLog(localDir: String, branch: String, startRev: String, endRev: String): Seq[String] = {
    withRepository(localDir) { repository =>
      val startId: AnyObjectId = resolveId(localDir, branch, startRev)
      val endId: AnyObjectId = resolveId(localDir, branch, endRev)
      // println(s"Logging from $startId to $endId")

      val logWalk = new Git(repository).log().addRange(startId, endId).call()
      val iterator = logWalk.iterator()

      @scala.annotation.tailrec
      def walkBackwards(results: Seq[String], next: AnyObjectId): Seq[String] = {
        if (iterator.hasNext) {
          val commit = iterator.next()
          val current = commit.getId
          if (current == next) {
            walkBackwards(results :+ next.name, commit.getParent(0).getId)
          } else {
            walkBackwards(results, next)
          }
        } else results
      }
      walkBackwards(Seq.empty, endId)
    }
  }

}