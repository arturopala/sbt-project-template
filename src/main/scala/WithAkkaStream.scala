import scala.concurrent.{ Future, Promise, Await }
import scala.util.{ Try, Success, Failure }
import scala.util.control.NonFatal
import scala.concurrent.duration._
import akka.actor.{ ActorSystem, Cancellable }
import akka.event.Logging
import akka.stream._
import akka.stream.scaladsl._
import FlowGraph.Implicits._

trait WithAkkaStream {

  implicit val akkaSystem = akka.actor.ActorSystem()
  implicit val materializer = akka.stream.ActorMaterializer()
  implicit val log = Logging(akkaSystem, "")
  implicit val executionContext = scala.concurrent.ExecutionContext.Implicits.global

  def await(future: Future[_]): Unit = {
    // shutdown when done
    future andThen {
      case Success(_) ⇒
        println("Done.")
        akkaSystem.shutdown()
      case Failure(e) =>
        println(s"Error: $e")
        akkaSystem.shutdown()
    }
  }

  def debug[A](a: A): A = { println(a); a }

}