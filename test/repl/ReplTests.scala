package repl

import notebook.kernel.repl.common.ReplT
import notebook.kernel.{Failure, Incomplete, Success, Repl}
import notebook.util.Match
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import utils.BeforeAllAfterAll

@RunWith(classOf[JUnitRunner])
class ReplTests extends Specification with BeforeAllAfterAll {

  var repl: ReplT = _
  def beforeAll = repl = new Repl()
  def afterAll = repl.stop()

  "ReplT can build Repl" in {
    ReplT.create(Nil, Nil)
    success
  }

  "fail when attempting to use undefined code" in {
    repl.evaluate("val x = new NotDefinedClass") match {
      case ( Failure(_), _ ) => success
      case _ => failure("Expected Failure from evaluation")
    }
  }

  "fail with incomplete code" in {
    repl.evaluate("case class ReplTestClass(va") match {
      case ( Incomplete, _ ) => success
      case _ => failure("Expected Incomplete from evaluation")
    }
  }

  "successfully evaluate coplete code" in {
    repl.evaluate("case class ReplTestClass(val x:Int)") match {
      case ( Success(_), _ ) => success
      case _ => failure("Expected Success from evaluation")
    }
  }

  "return a type name of defined term" in {
    repl.evaluate("case class ReplTestClassGetTypeName(val x:Int); val x = new ReplTestClassGetTypeName(5)") match {
      case ( Success(_), _ ) =>
        repl.getTypeNameOfTerm("x") match {
          case None =>
            failure("Expected Some type name")
          case Some(typeName) =>
            typeName must contain("ReplTestClassGetTypeName")
            success
        }
      case _ =>
        failure("Expected Success from evaluation")
    }
  }

  "do not render the last defined variable when cell returns nothing" in {
    repl.evaluate("val seq = Seq(1, 2, 3)") match {
      case ( Success(resultWidgets), _ ) =>
        resultWidgets.size must beEqualTo(0)
        success
      case _ =>
        failure("Expected not to render the last defined variable")
    }
  }

  "render the return value of a cell (if one exists)" in {
    repl.evaluate("Seq(1, 2, 3)") match {
      case ( Success(resultWidgets), _ ) =>
        resultWidgets.size must beEqualTo(1)
        success
      case _ =>
        failure("Expected to render the result value")
    }
  }

  "return object information for code" in {
    var code =
      """
        |case class ReplTestClassInformation(val prop:Int) {
        |  def dummy: Int = prop
        |}
        |val x = new ReplTestClassInformation(5)
      """.stripMargin
    repl.evaluate(code) match {
      case ( Success(_), _ ) =>
        repl.objectInfo("x.du", 4) must beEqualTo( List("dummy") )
        success
      case _ =>
        failure("Expected Success from evaluation")
    }
  }

  "return completed line for code" in {
    var code =
      """
        |case class ReplTestClassCompletion(val prop:Int) {
        |  def dummy: Int = prop
        |}
        |val x = new ReplTestClassCompletion(5)
      """.stripMargin
    repl.evaluate(code) match {
      case ( Success(_), _ ) =>
        var completed = repl.complete("x.du", 4)
        completed must beEqualTo( ("du", List( Match("dummy", Map.empty[String, String]) ) ) )
        success
      case _ =>
        failure("Expected Success from evaluation")
    }
  }

  "adding a jar must keep defined code intact" in {
    if ( sys.env.contains("SKIP_WHEN_TRAVIS") ) {
      skipped(": Test skipped on TravisCI, causes OOM.")
    } else {
      val privRepl = new Repl()
      privRepl.evaluate("case class MyTestClassToKeep()")
      val replInit = privRepl.addCp(List.empty[String])
      val newRepl = replInit._1
      replInit._2.apply() // calls the replay logic on the new repl...
      val res = newRepl.evaluate("val x = new MyTestClassToKeep") match {
        case (Success(_), _) =>
          success
        case ex =>
          failure(s"Expected Success from evaluation but received $ex")
      }
      newRepl.stop()
      res
    }
  }

}
