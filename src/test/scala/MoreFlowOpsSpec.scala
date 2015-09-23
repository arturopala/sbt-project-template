import org.scalatest.{ WordSpecLike, Matchers }
import org.scalatest.prop.PropertyChecks
import org.scalatest.junit.JUnitRunner
import org.scalacheck._
import org.scalatest.matchers._
import org.scalatest.concurrent.ScalaFutures._
import scala.concurrent.duration._
import scala.concurrent.{ Future, Await }
import scala.util.{ Try, Success, Failure }
import scala.util.control.NonFatal
import akka.actor.{ ActorSystem, Cancellable }
import akka.stream._
import akka.stream.scaladsl._
import FlowGraph.Implicits._
import akka.stream.testkit.scaladsl._

class MoreFlowOpsSpec extends WordSpecLike with Matchers with PropertyChecks {

  implicit override val generatorDrivenConfig = PropertyCheckConfig(minSize = 1, maxSize = 100, minSuccessful = 100, workers = 5)

  implicit val system = akka.actor.ActorSystem()
  implicit val materializer = akka.stream.ActorMaterializer()

  "A MoreFlowOps" must {

    import MoreFlowOps._

    "provide `zip` function" which {

      "zip elements coming from both streams (of equal sizes)" in {
        val s1 = Source(1 to 10)
        val s2 = Source(10 to (1, -1))
        s1.zip(s2).runWith(TestSink.probe[(Int, Int)])
          .request(10)
          .expectNext((1, 10), (2, 9), (3, 8), (4, 7), (5, 6), (6, 5), (7, 4), (8, 3), (9, 2), (10, 1))
          .expectComplete()
      }

      "zip elements coming from both streams (left stream shorter)" in {
        val s1 = Source(1 to 5)
        val s2 = Source(10 to (1, -2))
        s1.zip(s2).runWith(TestSink.probe[(Int, Int)])
          .request(10)
          .expectNext((1, 10), (2, 8), (3, 6), (4, 4), (5, 2))
          .expectComplete()
      }

      "zip elements coming from both streams (right stream shorter)" in {
        val s1 = Source(1 to 10)
        val s2 = Source(10 to (4, -2))
        s1.zip(s2).runWith(TestSink.probe[(Int, Int)])
          .request(10)
          .expectNext((1, 10), (2, 8), (3, 6), (4, 4))
          .expectComplete()
      }
    }
  }

}