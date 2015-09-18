import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl._
import scala.concurrent.{ Future }
import scala.util.{ Try, Success, Failure }

object TcpSocketDemoApp extends App {

  import akka.stream.scaladsl.Tcp
  import akka.stream.scaladsl.Tcp._
  import akka.util.ByteString
  import akka.stream.io.Framing

  implicit val system = akka.actor.ActorSystem()
  implicit val materializer = akka.stream.ActorMaterializer()

  val tcpHandlingFlow = Flow[ByteString]
    .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true))
    .map(_.utf8String)
    .map(_+"!!!\n")
    .map(ByteString(_))

  val bindingFuture: Future[ServerBinding] = Tcp().bindAndHandle(tcpHandlingFlow, "127.0.0.1", 8888)

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  Console.readLine()

  import system.dispatcher // for the future transformations
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ ⇒ system.shutdown()) // and shutdown when done

}