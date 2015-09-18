object AkkaStreamExamples {

  import scala.concurrent.{ Future, Promise }
  import scala.util.{ Try, Success, Failure }
  import scala.util.control.NonFatal
  import akka.actor.ActorSystem
  import akka.stream.scaladsl._
  import akka.actor.Cancellable

  implicit val system = akka.actor.ActorSystem()
  implicit val materializer = akka.stream.ActorMaterializer()

  import scala.concurrent.ExecutionContext.Implicits.global

}