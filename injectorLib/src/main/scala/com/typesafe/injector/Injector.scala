package com.typesafe.injector

import org.rogach.scallop._
import org.rogach.scallop.exceptions.ScallopException
import org.apache.ivy.util.ChecksumHelper
import sbt.PathFinder._
import sbt.Path._
import sbt.IO._
import sbt.GlobFilter
import sbt.DirectoryFilter
import java.io.File
import java.io.InputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.jar.JarFile
import java.util.jar.JarEntry
import collection.JavaConversions._
import org.rogach.scallop.exceptions.RequiredOptionNotFound

case class Exit(val code: Int) extends xsbti.Exit
object Injector {
  def main(args: Array[String]) = (new Injector).start(args)
  def apply(args: String*) = main(args.toArray)
}
class Injector extends xsbti.AppMain {
  def run(configuration: xsbti.AppConfiguration) = {
    try {
      // configuration.provider.id.name will be "injector-lib"
      start(configuration.arguments)
      Exit(0)
    } catch {
      case e: Exception =>
        e.printStackTrace
        Exit(1)
    }
  }
  def start(args: Array[String]) = {
    class conf extends ScallopConf(args.toList) {
      printedName = Defaults.name
      version("Typesafe " + Defaults.name + " " + Defaults.version)
      banner(("""Usage: """ + Defaults.name + """ [OPTIONS]
                |""" + (Defaults.name.capitalize) + """ is a simple tool that will inject additional files into a set of
                |artifact jar files, and recalculate their checksum files appropriately.
                |Options:
                |""").stripMargin)
      footer("""
               |For most options, you can specify multiple files or paths separated by blanks,
               |or multiple times the same option to add elements.
               |For additional information, please contact http://www.typesafe.com.""".stripMargin)
      val debug = opt[Boolean](noshort = true, descr = "Print more debugging information")
      private def diagnose(message: String) = {
        if (System.console() == null) {
          // no colors on output
          println("[%s] Error: %s" format (printedName, message))
        } else {
          println("[\u001b[31m%s\u001b[0m] Error: %s" format (printedName, message))
        }
      }
      private def fileExists(p: String, dirs: Boolean) = {
        val f = new File(p)
        val valid = f.exists
        if (!valid) diagnose((if (dirs) "Directory" else "File") + " not found: " + f.getCanonicalPath)
        valid
      }
      val files = opt[List[String]](descr = "Path to the file(s) that should be inserted into jars. They will " +
        "by default be added at the root of the jar; if you would like them at a different location, please " +
        "append a \"@\" and the desired path, as in \"manifest.txt@META-INF/MANIFEST.MF\". If the destination path " +
        "specifies just a directory, please append '/' to the path string.",
        required = true, validate = (_.map(_.split('@').head).forall(fileExists(_, dirs = false))))
      val directories = opt[List[String]](required = true, descr = "One or more paths to the directories containing the jars " +
        "that will be processed. Every directory will be scanned recursively. In place of a directory, you can specify " +
        "individual jar files.", validate = (_.forall(fileExists(_, dirs = true))))
      val jars = opt[List[String]](descr = "Patterns that specify which jars should be considered, in glob format. " +
        "For instance, c*.jar will match all jars whose basename begins with c. If multiple patterns are specified, " +
        "all the jars that match at least one pattern will be considered. All patterns must end with \".jar\". " +
        "If omitted, all jars will be processed.",
        validate = (_.forall { j =>
          val valid = j.endsWith(".jar") && !j.contains("/")
          if (!valid) {
            diagnose("This pattern contains a forward slash, or does not end with \".jar\": " + j)
          }
          valid
        }),
        default = Some(List[String]("*.jar")))
      val noChecksums = opt[Boolean](descr = "Do not regenerate the checksum files of the modified jar files. By default, " +
        "new mds and sha1 files will be generated, replacing the old ones.")
      val to = opt[String](descr = "By default, the jar files will be overwritten in place. If you would like to " +
        "preserve the originals, you can specify using this option a directory where the new files will be stored. The " +
        "destination directory will be created, if it does not exist yet.", validate = { to =>
        val valid = !new File(to).isFile
        if (!valid) {
          diagnose("The selected destination already exists, and is a file")
        }
        valid
      })
      // if a required argument is missing, print the help text before the error message
      override protected def onError(e: Throwable) = e match {
        case x @ RequiredOptionNotFound(name) =>
          printHelp
          super.onError(x)
        // other exceptions handling
        case x => super.onError(x)
      }
      val quiet = opt[Boolean](descr = "Do not print messages on the console.")
      // Note: the tool will currently not re-sign previously signed jars
    }
    val conf = new conf
    val debug = conf.debug()
    val filesAndTargets = conf.files().map { fileAndTarget =>
      val (filePath, targetBase) = fileAndTarget.split("@", 2) match {
        case Array(f) => (f, "")
        case Array(f, t) => (f, t)
      }
      val file = new File(filePath)
      val target = if (targetBase == "" || targetBase.endsWith("/"))
        targetBase + file.getName
      else
        targetBase
      (file, target)
    }
    val patterns = conf.jars()
    val dirs = conf.directories()
    val checksums = !conf.noChecksums()
    val quiet = conf.quiet() && !debug

    // Are we specifying a new destination directory?
    val to = conf.to.get
    val jarDirs = to match {
      case Some(toDir) =>
        val dest = new File(toDir)
        dest.mkdirs()
        if (debug) println("Copying into " + toDir + ":")
        dirs foreach { d =>
          // copy the dirs (or individual jars) into the "to" destination directory
          val f = new File(d)
          if (f.isFile)
            copyFile(f, dest / f.getName, preserveLastModified = true)
          else
            copyDirectory(f, dest, overwrite = true, preserveLastModified = true)
          if (debug) println("... " + d)
        }
        Seq(toDir)
      case None => dirs
    }

    // Let's find out which jars need to be processed. This code will work
    // equally well if an element of "dirs" is a jar file, rather than a directory.
    //
    val filter = patterns.map(GlobFilter(_)).reduce(_ | _).--(DirectoryFilter)
    val jars = jarDirs.map(new File(_).**(filter)).reduce(_ +++ _).get // APL?

    val targets = filesAndTargets.map(_._2)
    jars foreach { jar =>
      if (!quiet) println("Rewriting: " + jar.getCanonicalPath + "")
      withTemporaryFile("injector", "tempJar") { temp =>
        val out = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))

        // Initially we copy the old jar content, and later we append the new entries
        val bufferSize = 131072
        val buffer = new Array[Byte](bufferSize)
        def writeEntry(where: JarEntry, source: InputStream) = {
          if (debug) println("writing entry: " + where.getName)
          out.putNextEntry(where)
          Stream.continually(source.read(buffer, 0, bufferSize)).takeWhile(_ != -1).
            foreach { size =>
              if (debug) println("Read " + size + " bytes")
              out.write(buffer, 0, size)
            }
        }
        val in = new JarFile(jar)
        //
        // The jar may contain duplicate entries (even though it shouldn't);
        // in particular, the scalap jar in Scala 2.11.4 is broken.
        // Rather than aborting, we print a warning and try to continue
        val list = in.entries.toSeq
        val uniques = list.foldLeft(Map[String, JarEntry]()) { (map, entry) =>
          if (map.isDefinedAt(entry.getName)) {
            if (!quiet) println("*WARNING* In file " + jar.getCanonicalPath +
              ", an illegal duplicate entry will be removed: " + entry.getName)
            map
          } else
            map.updated(entry.getName, entry)
        }

        // Copy all the content, skipping the entries that will be replaced
        uniques.valuesIterator.foreach { entry =>
          if (debug) println("Found entry " + entry.getName + ", size: " + entry.getSize)
          if (!targets.contains(entry.getName())) {
            writeEntry(entry, in.getInputStream(entry))
          }
        }

        // Finally, insert the new entries at the appropriate target locations
        filesAndTargets.foreach {
          case (file, target) =>
            writeEntry(new JarEntry(target), new BufferedInputStream(new FileInputStream(file)))
        }
        in.close()
        out.flush()
        out.close()

        // Time to move the temporary file back to the original location
        move(temp, jar)

        // Do we need to regenerate the checksum files?
        if (checksums) {
          Seq("md5", "sha1") foreach { algorithm =>
            val checksumFile = new File(jar.getCanonicalPath + "." + algorithm)
            if (checksumFile.exists) {
              write(checksumFile, ChecksumHelper.computeAsString(jar, algorithm))
            }
          }
        }
      }
    }
  }
}
