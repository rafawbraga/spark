/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.connect.artifact

import java.io.File
import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.CopyOnWriteArrayList
import javax.ws.rs.core.UriBuilder

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

import org.apache.commons.io.FileUtils
import org.apache.hadoop.fs.{LocalFileSystem, Path => FSPath}

import org.apache.spark.{JobArtifactSet, SparkContext, SparkEnv}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.connect.artifact.util.ArtifactUtils
import org.apache.spark.sql.connect.config.Connect.CONNECT_COPY_FROM_LOCAL_TO_FS_ALLOW_DEST_LOCAL
import org.apache.spark.sql.connect.service.SessionHolder
import org.apache.spark.storage.{CacheId, StorageLevel}
import org.apache.spark.util.Utils

/**
 * The Artifact Manager for the [[SparkConnectService]].
 *
 * This class handles the storage of artifacts as well as preparing the artifacts for use.
 *
 * Artifacts belonging to different [[SparkSession]]s are segregated and isolated from each other
 * with the help of the `sessionUUID`.
 *
 * Jars and classfile artifacts are stored under "jars" and "classes" sub-directories respectively
 * while other types of artifacts are stored under the root directory for that particular
 * [[SparkSession]].
 *
 * @param sessionHolder
 *   The object used to hold the Spark Connect session state.
 */
class SparkConnectArtifactManager(sessionHolder: SessionHolder) extends Logging {
  import SparkConnectArtifactManager._

  private val sessionUUID = sessionHolder.session.sessionUUID
  // The base directory/URI where all artifacts are stored for this `sessionUUID`.
  val (artifactPath, artifactURI): (Path, String) =
    getArtifactDirectoryAndUriForSession(sessionHolder)
  // The base directory/URI where all class file artifacts are stored for this `sessionUUID`.
  val (classDir, classURI): (Path, String) = getClassfileDirectoryAndUriForSession(sessionHolder)

  private val jarsList = new CopyOnWriteArrayList[Path]
  private val jarsURI = new CopyOnWriteArrayList[String]
  private val pythonIncludeList = new CopyOnWriteArrayList[String]

  /**
   * Get the URLs of all jar artifacts added through the [[SparkConnectService]].
   *
   * @return
   */
  def getSparkConnectAddedJars: Seq[URL] = jarsList.asScala.map(_.toUri.toURL).toSeq

  /**
   * Get the py-file names added through the [[SparkConnectService]].
   *
   * @return
   */
  def getSparkConnectPythonIncludes: Seq[String] = pythonIncludeList.asScala.toSeq

  /**
   * Add and prepare a staged artifact (i.e an artifact that has been rebuilt locally from bytes
   * over the wire) for use.
   *
   * @param remoteRelativePath
   * @param serverLocalStagingPath
   * @param fragment
   */
  private[connect] def addArtifact(
      remoteRelativePath: Path,
      serverLocalStagingPath: Path,
      fragment: Option[String]): Unit = {
    require(!remoteRelativePath.isAbsolute)
    if (remoteRelativePath.startsWith(s"cache${File.separator}")) {
      val tmpFile = serverLocalStagingPath.toFile
      Utils.tryWithSafeFinallyAndFailureCallbacks {
        val blockManager = sessionHolder.session.sparkContext.env.blockManager
        val blockId = CacheId(
          userId = sessionHolder.userId,
          sessionId = sessionHolder.sessionId,
          hash = remoteRelativePath.toString.stripPrefix(s"cache${File.separator}"))
        val updater = blockManager.TempFileBasedBlockStoreUpdater(
          blockId = blockId,
          level = StorageLevel.MEMORY_AND_DISK_SER,
          classTag = implicitly[ClassTag[Array[Byte]]],
          tmpFile = tmpFile,
          blockSize = tmpFile.length(),
          tellMaster = false)
        updater.save()
      }(catchBlock = { tmpFile.delete() })
    } else if (remoteRelativePath.startsWith(s"classes${File.separator}")) {
      // Move class files to the right directory.
      val target = ArtifactUtils.concatenatePaths(
        classDir,
        remoteRelativePath.toString.stripPrefix(s"classes${File.separator}"))
      Files.createDirectories(target.getParent)
      // Allow overwriting class files to capture updates to classes.
      // This is required because the client currently sends all the class files in each class file
      // transfer.
      Files.move(serverLocalStagingPath, target, StandardCopyOption.REPLACE_EXISTING)
    } else {
      val target = ArtifactUtils.concatenatePaths(artifactPath, remoteRelativePath)
      Files.createDirectories(target.getParent)
      // Disallow overwriting non-classfile artifacts
      if (Files.exists(target)) {
        throw new RuntimeException(
          s"Duplicate Artifact: $remoteRelativePath. " +
            "Artifacts cannot be overwritten.")
      }
      Files.move(serverLocalStagingPath, target)
      if (remoteRelativePath.startsWith(s"jars${File.separator}")) {
        jarsList.add(target)
        jarsURI.add(artifactURI + "/" + target.toString)
      } else if (remoteRelativePath.startsWith(s"pyfiles${File.separator}")) {
        sessionHolder.session.sparkContext.addFile(target.toString)
        val stringRemotePath = remoteRelativePath.toString
        if (stringRemotePath.endsWith(".zip") || stringRemotePath.endsWith(
            ".egg") || stringRemotePath.endsWith(".jar")) {
          pythonIncludeList.add(target.getFileName.toString)
        }
      } else if (remoteRelativePath.startsWith(s"archives${File.separator}")) {
        val canonicalUri =
          fragment.map(UriBuilder.fromUri(target.toUri).fragment).getOrElse(target.toUri)
        sessionHolder.session.sparkContext.addArchive(canonicalUri.toString)
      } else if (remoteRelativePath.startsWith(s"files${File.separator}")) {
        sessionHolder.session.sparkContext.addFile(target.toString)
      }
    }
  }

  /**
   * Returns a [[JobArtifactSet]] pointing towards the session-specific jars and class files.
   */
  def jobArtifactSet: JobArtifactSet = {
    val builder = Map.newBuilder[String, Long]
    jarsURI.forEach { jar =>
      builder += jar -> 0
    }

    new JobArtifactSet(
      uuid = Option(sessionUUID),
      replClassDirUri = Option(classURI),
      jars = builder.result(),
      files = Map.empty,
      archives = Map.empty)
  }

  /**
   * Returns a [[ClassLoader]] for session-specific jar/class file resources.
   */
  def classloader: ClassLoader = {
    val urls = jarsList.asScala.map(_.toUri.toURL) :+ classDir.toUri.toURL
    new URLClassLoader(urls.toArray, Utils.getContextOrSparkClassLoader)
  }

  /**
   * Cleans up all resources specific to this `sessionHolder`.
   */
  private[connect] def cleanUpResources(): Unit = {
    logDebug(
      s"Cleaning up resources for session with userId: ${sessionHolder.userId} and " +
        s"sessionId: ${sessionHolder.sessionId}")
    // Clean up cached relations
    val blockManager = sessionHolder.session.sparkContext.env.blockManager
    blockManager.removeCache(sessionHolder.userId, sessionHolder.sessionId)

    // Clean up artifacts folder
    FileUtils.deleteDirectory(artifactRootPath.toFile)
  }

  private[connect] def uploadArtifactToFs(
      remoteRelativePath: Path,
      serverLocalStagingPath: Path): Unit = {
    val hadoopConf = sessionHolder.session.sparkContext.hadoopConfiguration
    assert(
      remoteRelativePath.startsWith(
        SparkConnectArtifactManager.forwardToFSPrefix + File.separator))
    val destFSPath = new FSPath(
      Paths
        .get("/")
        .resolve(remoteRelativePath.subpath(1, remoteRelativePath.getNameCount))
        .toString)
    val localPath = serverLocalStagingPath
    val fs = destFSPath.getFileSystem(hadoopConf)
    if (fs.isInstanceOf[LocalFileSystem]) {
      val allowDestLocalConf =
        SparkEnv.get.conf.get(CONNECT_COPY_FROM_LOCAL_TO_FS_ALLOW_DEST_LOCAL)
      if (!allowDestLocalConf) {
        // To avoid security issue, by default,
        // we don't support uploading file to local file system
        // destination path, otherwise user is able to overwrite arbitrary file
        // on spark driver node.
        // We can temporarily allow the behavior by setting spark config
        // `spark.connect.copyFromLocalToFs.allowDestLocal`
        // to `true` when starting spark driver, we should only enable it for testing
        // purpose.
        throw new UnsupportedOperationException(
          "Uploading artifact file to local file system destination path is not supported.")
      }
    }
    fs.copyFromLocalFile(false, true, new FSPath(localPath.toString), destFSPath)
  }
}

object SparkConnectArtifactManager extends Logging {

  val forwardToFSPrefix = "forward_to_fs"

  private var currentArtifactRootUri: String = _
  private var lastKnownSparkContextInstance: SparkContext = _

  private val ARTIFACT_DIRECTORY_PREFIX = "artifacts"

  // The base directory where all artifacts are stored.
  private[spark] lazy val artifactRootPath = {
    Utils.createTempDir(ARTIFACT_DIRECTORY_PREFIX).toPath
  }

  private[spark] def getArtifactDirectoryAndUriForSession(session: SparkSession): (Path, String) =
    (
      ArtifactUtils.concatenatePaths(artifactRootPath, session.sessionUUID),
      s"$artifactRootURI/${session.sessionUUID}")

  private[spark] def getArtifactDirectoryAndUriForSession(
      sessionHolder: SessionHolder): (Path, String) =
    getArtifactDirectoryAndUriForSession(sessionHolder.session)

  private[spark] def getClassfileDirectoryAndUriForSession(
      session: SparkSession): (Path, String) = {
    val (artDir, artUri) = getArtifactDirectoryAndUriForSession(session)
    (ArtifactUtils.concatenatePaths(artDir, "classes"), s"$artUri/classes/")
  }

  private[spark] def getClassfileDirectoryAndUriForSession(
      sessionHolder: SessionHolder): (Path, String) =
    getClassfileDirectoryAndUriForSession(sessionHolder.session)

  /**
   * Updates the URI for the artifact directory.
   *
   * This is required if the SparkContext is restarted.
   *
   * Note: This logic is solely to handle testing where a [[SparkContext]] may be restarted
   * several times in a single JVM lifetime. In a general Spark cluster, the [[SparkContext]] is
   * not expected to be restarted at any point in time.
   */
  private def refreshArtifactUri(sc: SparkContext): Unit = synchronized {
    // If a competing thread had updated the URI, we do not need to refresh the URI again.
    if (sc eq lastKnownSparkContextInstance) {
      return
    }
    val oldArtifactUri = currentArtifactRootUri
    currentArtifactRootUri = SparkEnv.get.rpcEnv.fileServer
      .addDirectoryIfAbsent(ARTIFACT_DIRECTORY_PREFIX, artifactRootPath.toFile)
    lastKnownSparkContextInstance = sc
    logDebug(s"Artifact URI updated from $oldArtifactUri to $currentArtifactRootUri")
  }

  /**
   * Checks if the URI for the artifact directory needs to be updated. This is required in cases
   * where SparkContext is restarted as the old URI would no longer be valid.
   *
   * Note: This logic is solely to handle testing where a [[SparkContext]] may be restarted
   * several times in a single JVM lifetime. In a general Spark cluster, the [[SparkContext]] is
   * not expected to be restarted at any point in time.
   */
  private def updateUriIfRequired(): Unit = {
    SparkContext.getActive.foreach { sc =>
      if (lastKnownSparkContextInstance == null || (sc ne lastKnownSparkContextInstance)) {
        logDebug("Refreshing artifact URI due to SparkContext (re)initialisation!")
        refreshArtifactUri(sc)
      }
    }
  }

  private[connect] def artifactRootURI: String = {
    updateUriIfRequired()
    require(currentArtifactRootUri != null)
    currentArtifactRootUri
  }
}
