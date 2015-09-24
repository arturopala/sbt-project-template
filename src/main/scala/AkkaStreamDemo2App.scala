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

  val source2 = io.InputStreamSource(() => new URL(url).openStream(), chunkSize = 128).map(_.decodeString("utf-8"))

  val process = source2
    .buffer(1, OverflowStrategy.backpressure)
    .throttle(1.millis, 15.minutes)
    .mapAsync(10)(text => Future { text.toUpperCase })
    .map(debug)

  val future = process.runWith(Sink.ignore)

  await(future)
}