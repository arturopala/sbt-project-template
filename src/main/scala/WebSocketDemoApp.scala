import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl._

object WebSocketDemoApp extends App {

  import akka.http.scaladsl.model.ws._

  implicit val system = akka.actor.ActorSystem()
  implicit val materializer = akka.stream.ActorMaterializer()

  val websocketService = Flow[Message].mapConcat {
    // we match but don't actually consume the text message here,
    // rather we simply stream it back as the tail of the response
    // this means we might start sending the response even before the
    // end of the incoming message has been received
    case tm: TextMessage ⇒ TextMessage(Source.single("Hello ") ++ tm.textStream) :: Nil
    case bm: BinaryMessage =>
      // ignore binary messages but drain content to avoid the stream being clogged
      bm.dataStream.runWith(Sink.ignore)
      Nil
  }

  import akka.http.scaladsl.server.Directives._

  val route = path("ws") {
    get {
      handleWebsocketMessages(websocketService)
    }
  }

  val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  Console.readLine()

  import system.dispatcher // for the future transformations
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ ⇒ system.shutdown()) // and shutdown when done

}