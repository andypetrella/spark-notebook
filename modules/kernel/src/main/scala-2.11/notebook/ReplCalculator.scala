package notebook
package client

import java.io.{File, FileWriter}

import org.joda.time.LocalDateTime

import scala.collection.immutable.Queue
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Try, Success=>TSuccess, Failure=>TFailure}

import akka.actor.{Actor, ActorRef, ActorLogging, Props}
import kernel._

import org.sonatype.aether.repository.RemoteRepository

import notebook.OutputTypes._
import notebook.util.{Deps, Match, Repos}
import notebook.front._
import notebook.front.widgets._


/**
 * @param initScripts List of scala source strings to be executed during REPL startup.
 * @param customSparkConf Map configuring the notebook (spark configuration).
 * @param compilerArgs Command line arguments to pass to the REPL compiler
 */
class ReplCalculator(
  notebookName:String,
  customLocalRepo:Option[String],
  customRepos:Option[List[String]],
  customDeps:Option[List[String]],
  customImports:Option[List[String]],
  customArgs:Option[List[String]],
  customSparkConf:Option[Map[String, String]],
  remoteActor:ActorRef,
  initScripts: List[(String, String)],
  compilerArgs: List[String]
) extends Actor with akka.actor.ActorLogging {

  private val remoteLogger = context.actorSelection("/user/remote-logger")
  remoteLogger ! remoteActor

  private val repoRegex = "(?s)^:local-repo\\s*(.+)\\s*$".r
  private val remoteRegex = "(?s)^:remote-repo\\s*(.+)\\s*$".r
  private val authRegex = """(?s)^\s*\(([^\)]+)\)\s*$""".r
  private val credRegex = """"([^"]+)"\s*,\s*"([^"]+)"""".r //"

  private def outputTypesRegex(ctx:String, outputType:String) = s"(?s)^:$ctx\\s*\n(.+)\\s*$$".r → outputType
  private val htmlContext       = outputTypesRegex("html",       `text/html`)
  private val plainContext      = outputTypesRegex("plain",      `text/plain`)
  private val markdownContext   = outputTypesRegex("markdown",   `text/markdown`)
  private val latexContext      = outputTypesRegex("latex",      `text/latex`)
  private val svgContext        = outputTypesRegex("svg",        `image/svg+xml`)
  private val pngContext        = outputTypesRegex("png",        `image/png`)
  private val jpegContext       = outputTypesRegex("jpeg",       `image/jpeg`)
  private val pdfContext        = outputTypesRegex("pdf",        `application/pdf`)
  private val javascriptContext = outputTypesRegex("javascript", `application/javascript`)

  private val cpRegex = "(?s)^:cp\\s*(.+)\\s*$".r
  private val dpRegex = "(?s)^:(l?)dp\\s*(.+)\\s*$".r
  private val sqlRegex = "(?s)^:sql(?:\\[([a-zA-Z0-9][a-zA-Z0-9]*)\\])?\\s*(.+)\\s*$".r
  private val shRegex = "(?s)^:sh\\s*(.+)\\s*$".r

  private def remoreRepo(r:String):(String, RemoteRepository) = {
    val id::tpe::url::remaining = r.split("%").toList
    val rest = remaining.map(_.trim) match {
      case Nil => remaining
      case "maven"::r => r //skip the flavor → always maven in 2.11
      case xs => xs
    }
    val (username, password):(Option[String],Option[String]) = rest.headOption.map { auth =>
      auth match {
        case authRegex(usernamePassword)   =>
          val (username, password) = usernamePassword match { case credRegex(username, password) => (username, password) }
          val u = if (username.startsWith("$")) sys.env.get(username.tail).get else username
          val p = if (password.startsWith("$")) sys.env.get(password.tail).get else password
          (Some(u), Some(p))
        case _                             => (None, None)
      }
    }.getOrElse((None, None))
    val rem = Repos(id.trim,tpe.trim,url.trim,username,password)
    val logR = r.replaceAll("\"", "\\\\\"")
    (logR, rem)
  }

  var remotes:List[RemoteRepository] = customRepos.getOrElse(List.empty[String]).map(remoreRepo _).map(_._2) :::
    List(Repos.mavenLocal, Repos.central, Repos.sparkPackages, Repos.oss)

  var repo:File = customLocalRepo.map { x =>
                    new File(notebook.util.StringUtils.updateWithVarEnv(x))
                  }.getOrElse {
                    val tmp = new File(System.getProperty("java.io.tmpdir"))

                    val snb = new File(tmp, "spark-notebook")
                    if (!snb.exists) snb.mkdirs

                    val aether = new File(snb, "aether")
                    if (!aether.exists) aether.mkdirs

                    val r = new File(aether, java.util.UUID.randomUUID.toString)
                    if (!r.exists) r.mkdirs

                    r
                  }

  def codeRepo = new File(repo, "code")

  val (depsJars, depsScript):(List[String],(String, ()=>String)) = customDeps.map { d =>
    val customDeps = d.mkString("\n")
    val deps = Deps.script(customDeps, remotes, repo).toOption.getOrElse(List.empty[String])
    (deps, ("deps", () => s"""
                    |val CustomJars = ${ deps.mkString("Array(\"", "\",\"", "\")").replace("\\","\\\\") }
                    |
                    """.stripMargin))
  }.getOrElse((List.empty[String], ("deps", () => "val CustomJars = Array.empty[String]\n")))

  val ImportsScripts = ("imports", () => customImports.map(_.mkString("\n") + "\n").getOrElse("\n"))

  private var _repl:Option[Repl] = None

  private def repl:Repl = _repl getOrElse {
    val r = new Repl(compilerArgs, depsJars)
    _repl = Some(r)
    r
  }

  val chat = new notebook.front.gadgets.Chat()

  // +/- copied of https://github.com/scala/scala/blob/v2.11.4/src%2Flibrary%2Fscala%2Fconcurrent%2Fduration%2FDuration.scala
  final def toCoarsest(d:FiniteDuration): String = {

    def loop(length: Long, unit: TimeUnit, acc:String): String = {

      def coarserOrThis(coarser: TimeUnit, divider: Int) = {
        if (length == divider)
          loop(1, coarser, acc)
        else if (length < divider)
          FiniteDuration(length, unit).toString + " " + acc
        else {
          val _acc = if (length % divider == 0) {
            acc
          } else {
            FiniteDuration(length % divider, unit).toString + " " + acc
          }
          loop(length / divider, coarser, _acc)
        }
      }

      unit match {
        case DAYS         => d.toString + " " + acc
        case HOURS        => coarserOrThis(DAYS, 24)
        case MINUTES      => coarserOrThis(HOURS, 60)
        case SECONDS      => coarserOrThis(MINUTES, 60)
        case MILLISECONDS => coarserOrThis(SECONDS, 1000)
        case MICROSECONDS => coarserOrThis(MILLISECONDS, 1000)
        case NANOSECONDS  => coarserOrThis(MICROSECONDS, 1000)
      }
    }

    if (d.unit == DAYS || d.length == 0) d.toString
    else loop(d.length, d.unit, "").trim
  }

  // Make a child actor so we don't block the execution on the main thread, so that interruption can work
  private val executor = context.actorOf(Props(new Actor {
    implicit val ec = context.dispatcher

    private var queue:Queue[(ActorRef, ExecuteRequest)] = Queue.empty
    private var currentlyExecutingTask: Option[Future[(String, EvaluationResult)]] = None

    def eval(b: => String, notify:Boolean=true)(success: => String = "", failure: String=>String=(s:String)=>"Error evaluating " + b + ": "+ s) {
      repl.evaluate(b)._1 match {
        case Failure(str) =>
          if (notify) {
            eval(s"""
            """,false)()
          }
          log.error(failure(str))
        case _ =>
          if (notify) {
            eval(s"""
            """,false)()
          }
          log.info(success)
      }
    }

    def receive = {
      case "process-next" =>
        log.debug(s"Processing next asked, queue is ${queue.size} length now")
        currentlyExecutingTask = None

        if (queue.nonEmpty) { //queue could be empty if InterruptRequest was asked!
          log.debug("Dequeuing execute request current size: " + queue.size)
          val (executeRequest, queueTail) = queue.dequeue
          queue = queueTail
          val (ref, er) = executeRequest
          log.debug("About to execute request from the queue")
          execute(ref, er)
        }

      case er@ExecuteRequest(_, _, code) =>
        log.debug("Enqueuing execute request at: " + queue.size)
        queue = queue.enqueue((sender(), er))

        // if queue contains only the new task, and no task is currently executing, execute it straight away
        // otherwise the execution will start once the evaluation of earlier cell(s) finishes
        if (currentlyExecutingTask.isEmpty && queue.size == 1) {
          self ! "process-next"
        }

      case InterruptCellRequest(killCellId) =>
        // kill job(s) still waiting for execution to start, if any
        val (jobsInQueueToKill, nonAffectedJobs) = queue.partition { case (_, ExecuteRequest(cellIdInQueue, _, _)) =>
          cellIdInQueue == killCellId
        }
        log.debug(s"Canceling $killCellId jobs still in queue (if any):\n $jobsInQueueToKill")
        queue = nonAffectedJobs

        log.debug(s"Interrupting the cell: $killCellId")
        val jobGroupId = JobTracking.jobGroupId(killCellId)
        // make sure sparkContext is already available!
        if (jobsInQueueToKill.isEmpty && repl.interp.allDefinedNames.exists(_.toString == "globalScope")) {
          log.info(s"Killing job Group $jobGroupId")
          val thisSender = sender()
          repl.evaluate(
            s"""globalScope.sparkContext.cancelJobGroup("${jobGroupId}")""",
            msg => thisSender ! StreamResponse(msg, "stdout")
          )
        }

        // StreamResponse shows error msg
        sender() ! StreamResponse(s"The cell was cancelled.\n", "stderr")
        // ErrorResponse to marks cell as ended
        sender() ! ErrorResponse(s"The cell was cancelled.\n", incomplete = false)


      case InterruptRequest =>
        log.debug("Interrupting the spark context")
        val thisSender = sender()
        log.debug("Clearing the queue of size " + queue.size)
        queue = scala.collection.immutable.Queue.empty
        repl.evaluate(
          "sparkContext.cancelAllJobs()",
          msg => {
            thisSender ! StreamResponse(msg, "stdout")
          }
        )
    }

    def execute(sender:ActorRef, er:ExecuteRequest):Unit = {
      val (outputType, newCode) = er.code match {
        case remoteRegex(r) =>
          log.debug("Adding remote repo: " + r)
          val (logR, remote) = remoreRepo(r)
          remotes = remote :: remotes
          (`text/plain`, s""" "Remote repo added: $logR!" """)

        case repoRegex(r) =>
          log.debug("Updating local repo: " + r)
          repo = new File(r.trim)
          repo.mkdirs
          (`text/plain`, s""" "Repo changed to ${repo.getAbsolutePath}!" """)

        case dpRegex(local, cp) =>
          log.debug(s"Fetching ${if(local == "l") "locally" else ""} deps using repos: " + remotes.mkString(" -- "))
          val tryDeps = Deps.script(cp, remotes, repo)

          tryDeps match {
            case TSuccess(deps) =>
              eval("""
                sparkContext.stop()
              """)(
                "CP reload processed successfully",
                (str:String) => "Error in :dp: \n%s".format(str)
              )
              val (_r, replay) = repl.addCp(deps)
              _repl = Some(_r)
              preStartLogic()
              replay()
              val newJarList = if (local == "l") {
                  "Nil"
                } else {
                  deps.map(x => x.replaceAll("\\\\", "\\\\\\\\")).mkString("List(\"", "\",\"", "\")")
                }
              (`text/html`,
                s"""
                   |//updating deps
                   |globalScope.jars = ($newJarList ::: globalScope.jars.toList).distinct.toArray
                   |//restarting spark
                   |reset()
                   |globalScope.jars.toList
                 """.stripMargin
              )
            case TFailure(ex) =>
              log.error(ex, "Cannot add dependencies")
              (`text/html`, s""" <p style="color:red">${ex.getMessage}</p> """)
          }

        case cpRegex(cp) =>
          val jars = cp.trim().split("\n").toList.map(_.trim()).filter(_.size > 0)
          repl.evaluate("""
            sparkContext.stop()
          """)._1 match {
            case Failure(str) =>
              log.error("Error in :cp: \n%s".format(str))
            case _ =>
              log.info("CP reload processed successfully")
          }
          val (_r, replay) = repl.addCp(jars)
          _repl = Some(_r)
          preStartLogic()
          replay()
          val newJarList = jars.map(x => x.replaceAll("\\\\", "\\\\\\\\")).mkString("List(\"", "\",\"", "\")")
          (`text/html`,
            s"""
              |//updating deps
              |globalScope.jars = ($newJarList ::: globalScope.jars.toList).distinct.toArray
              |//restarting spark
              |reset()
              |globalScope.jars.toList
            """.stripMargin
          )

        case shRegex(sh) =>
          val ps = "s\"\"\""+sh.replaceAll("\\s*\\|\\s*", "\" #\\| \"").replaceAll("\\s*&&\\s*", "\" #&& \"")+"\"\"\""
          val shCode =
            s"""|import sys.process._
                |println($ps.!!(ProcessLogger(out => (), err => println(err))))
                |()
                |""".stripMargin.trim
          log.debug(s"Generated SH code: $shCode")
          (`text/plain`, shCode)

        case sqlRegex(n, sql) =>
          log.debug(s"Received sql code: [$n] $sql")
          val qs = "\"\"\""
          val name = Option(n).map(nm => s"@transient val $nm = ").getOrElse ("")
          (`text/html`,
            s"""
            import notebook.front.widgets.Sql
            import notebook.front.widgets.Sql._
            ${name}new Sql(sqlContext, s$qs$sql$qs)
            """
          )

        case htmlContext._1(content)        =>
          val ctx = htmlContext._2
          val c = content.toString.replaceAll("\"", "&quot;")
          (ctx, " scala.xml.XML.loadString(s\"\"\""+c+"\"\"\") ")

        case plainContext._1(content)       =>
          val ctx = plainContext._2
          val c = content.toString.replaceAll("\"", "\\\\\\\"")
          (ctx, " s\"\"\""+c+"\"\"\" ")

        case markdownContext._1(content)    =>
          val ctx = markdownContext._2
          val c = content.toString.replaceAll("\\\"", "\"")
          (ctx, " s\"\"\""+c+"\"\"\" ")

        case latexContext._1(content)       =>
          val ctx = latexContext._2
          val c = content.toString.replaceAll("\\\"", "\"")
          (ctx, " s\"\"\""+c+"\"\"\" ")

        case svgContext._1(content)         =>
          val ctx = svgContext._2
          val c = content.toString.replaceAll("\"", "&quot;")
          (ctx, " scala.xml.XML.loadString(s\"\"\""+c+"\"\"\") ")

        case pngContext._1(content)         =>
          val ctx = pngContext._2
          (ctx, content.toString)

        case jpegContext._1(content)        =>
          val ctx = jpegContext._2
          (ctx, content.toString)

        case pdfContext._1(content)         =>
          val ctx = pdfContext._2
          (ctx, content.toString)

        case javascriptContext._1(content)  =>
          val ctx = javascriptContext._2
          val c = content.toString//.replaceAll("\"", "\\\"")
          (ctx, " s\"\"\""+c+"\"\"\" ")

        case whatever => (`text/html`, whatever)
      }

      val start = System.currentTimeMillis
      val thisSelf = self
      val thisSender = sender
      val result = scala.concurrent.Future {
        // this future is required to allow InterruptRequest messages to be received and process
        // so that spark jobs can be killed and the hand given back to the user to refine their tasks
        val cellId = er.cellId
        def replEvaluate(code:String, cellId:String) = {
          val cellResult = try {
           repl.evaluate(s"""
              |globalScope.sparkContext.setJobGroup("${JobTracking.jobGroupId(cellId)}", "${JobTracking.jobDescription(code, start)}")
              |$code
              """.stripMargin,
              msg => thisSender ! StreamResponse(msg, "stdout"),
              nameDefinition => thisSender ! nameDefinition
            )
          }
          finally {
             repl.evaluate("globalScope.sparkContext.clearJobGroup()")
          }
          cellResult
        }
        val result = replEvaluate(newCode, cellId)
        val d = toCoarsest(Duration(System.currentTimeMillis - start, MILLISECONDS))
        (d, result._1)
      }
      currentlyExecutingTask = Some(result)

      result foreach {
        case (timeToEval, Success(result)) =>
          val evalTimeStats = s"Took: $timeToEval, at ${new LocalDateTime().toString("Y-M-d H:m")}"
          thisSender ! ExecuteResponse(outputType, result.toString, evalTimeStats)
        case (timeToEval, Failure(stackTrace)) =>
          thisSender ! ErrorResponse(stackTrace, incomplete = false)
        case (timeToEval, notebook.kernel.Incomplete) =>
          thisSender ! ErrorResponse("Incomplete (hint: check the parenthesis)", incomplete = true)
      }

      result onComplete { _ =>
        thisSelf ! "process-next"
      }
    }
  }))

  def preStartLogic() {
    log.info("ReplCalculator preStart")

    val dummyScript = ("dummy", () => s"""val dummy = ();\n""")
    val SparkHookScript = ("class server", () => s"""@transient val _5C4L4_N0T3800K_5P4RK_HOOK = "${repl.classServerUri.get.replaceAll("\\\\", "\\\\\\\\")}";\n""")

    // Must escape last remaining '\', which could be for windows paths.
    val nbName = notebookName.replaceAll("\"", "").replace("\\", "\\\\")

    val SparkConfScript = {
      val m = customSparkConf .getOrElse(Map.empty[String, String])
      m .map{ case (k, v) =>
          "( \"" + k + "\"  → \"" + v + "\" )"
        }.mkString(",")
    }

    val CustomSparkConfFromNotebookMD = ("custom conf", () => s"""
      |@transient val notebookName = "$nbName"
      |@transient val _5C4L4_N0T3800K_5P4RK_C0NF:Map[String, String] = Map(
      |  $SparkConfScript
      |)\n
      """.stripMargin
    )

    def eval(script: () => String):Unit = {
      val sc = script()
      log.debug("script is :\n" + sc)
      if (sc.trim.length > 0) {
        val (result, _) = repl.evaluate(sc)
        result match {
          case Failure(str) =>
            log.error("Error in init script: \n%s".format(str))
          case _ =>
            if (log.isDebugEnabled) log.debug("\n" + sc)
            log.info("Init script processed successfully")
        }
      } else ()
    }

    val allInitScrips: List[(String, () => String)] = dummyScript :: SparkHookScript :: depsScript :: ImportsScripts :: CustomSparkConfFromNotebookMD :: initScripts.map(x => (x._1, () => x._2))
    for ((name, script) <- allInitScrips) {
      log.info(s" INIT SCRIPT: $name")
      eval(script)
    }
  }

  override def preStart() {
    preStartLogic()
    super.preStart()
  }

  override def postStop() {
    log.info("ReplCalculator postStop")
    super.postStop()
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    log.info("ReplCalculator preRestart " + message)
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

  override def postRestart(reason: Throwable) {
    log.info("ReplCalculator postRestart")
    reason.printStackTrace()
    super.postRestart(reason)
  }

  def receive = {
    case msgThatShouldBeFromTheKernel =>

      msgThatShouldBeFromTheKernel match {
        case req @ InterruptCellRequest(_) =>
          executor.forward(req)

        case InterruptRequest =>
          executor.forward(InterruptRequest)

        case req @ ExecuteRequest(_, _, code) => executor.forward(req)

        case CompletionRequest(line, cursorPosition) =>
          val (matched, candidates) = repl.complete(line, cursorPosition)
          sender ! CompletionResponse(cursorPosition, candidates, matched)

        case ObjectInfoRequest(code, position) =>
          val completions = repl.objectInfo(code, position)

          val resp = if (completions.length == 0) {
            ObjectInfoResponse(false, code, "", "")
          } else {
            ObjectInfoResponse(true, code, completions.mkString("\n"), "")
          }

          sender ! resp
      }
  }
}
