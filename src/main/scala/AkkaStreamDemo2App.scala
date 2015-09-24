import scala.concurrent.{ Future, Promise, Await }
import scala.util.{ Try, Success, Failure }
import scala.util.control.NonFatal
import scala.concurrent.duration._
import akka.actor.{ ActorSystem, Cancellable }
import akka.event.Logging
import akka.stream._
import akka.stream.scaladsl._
import FlowGraph.Implicits._
import java.net._
import akka.http._
import akka.http.scaladsl._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._

object AkkaStreamDemo2App extends App with WithAkkaStream {

  import MoreFlowOps._

  val url = args.lift(0).getOrElse("http://s3.eu-central-1.amazonaws.com/arturopala-test-bucket/kjbible.txt")
  val source: Source[String, Unit] = Source(() => scala.io.Source.fromURL(url).getLines)

  val source2 = io.InputStreamSource(() => new URL(url).openStream(), chunkSize = 1024).map(_.decodeString("utf-8"))

  val poolClientFlow = Http().cachedHostConnectionPool[String]("localhost", 8088,
    ConnectionPoolSettings(
      maxConnections = 10,
      maxRetries = 1,
      maxOpenRequests = 1,
      pipeliningLimit = 1,
      idleTimeout = 10.seconds,
      connectionSettings = ClientConnectionSettings(akkaSystem).copy(connectingTimeout = 1.millis, idleTimeout = 10.seconds)
    )
  )

  val future = source2
    .buffer(1, OverflowStrategy.backpressure)
    .throttle(15.millis)
    .map(line => (HttpRequest(method = HttpMethods.POST, uri = "/demo2", entity = HttpEntity(line)), line))
    .via(poolClientFlow).withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
    .map {
      case (Success(HttpResponse(StatusCodes.OK, _, _, _)), token) => s"OK      $token"
      case (_, token) => s"FAILURE $token"
    }
    .map(debug)
    .runWith(Sink.ignore)

  await(future)
}