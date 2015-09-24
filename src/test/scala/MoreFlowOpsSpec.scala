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

      "zip elements pulled from both streams of equal sizes" in {
        val s1 = Source(1 to 10)
        val s2 = Source(10 to (1, -1))
        s1.zip(s2).runWith(TestSink.probe[(Int, Int)])
          .request(10)
          .expectNext((1, 10), (2, 9), (3, 8), (4, 7), (5, 6), (6, 5), (7, 4), (8, 3), (9, 2), (10, 1))
          .expectComplete()
      }

      "zip elements pulled from both streams (left stream shorter)" in {
        val s1 = Source(1 to 5)
        val s2 = Source(10 to (1, -2))
        s1.zip(s2).runWith(TestSink.probe[(Int, Int)])
          .request(10)
          .expectNext((1, 10), (2, 8), (3, 6), (4, 4), (5, 2))
          .expectComplete()
      }

      "zip elements pulled from both streams (right stream shorter)" in {
        val s1 = Source(1 to 10)
        val s2 = Source(10 to (4, -2))
        s1.zip(s2).runWith(TestSink.probe[(Int, Int)])
          .request(10)
          .expectNext((1, 10), (2, 8), (3, 6), (4, 4))
          .expectComplete()
      }

      "zip elements pulled from both streams (left stream empty)" in {
        val s1 = Source.empty
        val s2 = Source(10 to (4, -2))
        s1.zip(s2).runWith(TestSink.probe[(Int, Int)])
          .request(10)
          .expectComplete()
      }

      "zip elements pulled from both streams (right stream empty)" in {
        val s1 = Source(1 to 10)
        val s2 = Source.empty
        s1.zip(s2).runWith(TestSink.probe[(Int, Int)])
          .request(10)
          .expectComplete()
      }

      "zip elements pulled from both streams empty" in {
        val s1 = Source.empty
        val s2 = Source.empty
        s1.zip(s2).runWith(TestSink.probe[(Int, Int)])
          .request(10)
          .expectComplete()
      }
    }

    "provide `join` function" which {

      "joins elements pulled from both streams of equal sizes" in {
        val s1 = Source(1 to 10)
        val s2 = Source(10 to (1, -1))
        s1.join(s2, (i: Int, j: Int) => i * j).runWith(TestSink.probe[Int])
          .request(10)
          .expectNext(10, 18, 24, 28, 30, 30, 28, 24, 18, 10)
          .expectComplete()
      }

      "joins elements pulled from both streams (left stream shorter)" in {
        val s1 = Source(1 to 5)
        val s2 = Source(10 to (1, -2))
        s1.join(s2, (i: Int, j: Int) => i * j).runWith(TestSink.probe[Int])
          .request(10)
          .expectNext(10, 16, 18, 16, 10)
          .expectComplete()
      }

      "joins elements pulled from both streams (right stream shorter)" in {
        val s1 = Source(1 to 10)
        val s2 = Source(10 to (4, -3))
        s1.join(s2, (i: Int, j: Int) => i * j).runWith(TestSink.probe[Int])
          .request(10)
          .expectNext(10, 14, 12)
          .expectComplete()
      }
    }

    "provide `zipWithLoop` function" which {

      "zips and pushes through flow elements pulled from both source and loopSource" in {
        val s1 = Source(1 to 10)
        val loopSource = Source(List("a", "b", "c", "d", "e", "f"))
        val flow = Flow[(Int, String)] map { case (i: Int, s: String) => (s * i, s) }
        val loop = s1.zipWithLoop(1, loopSource)(flow)
        loop.runWith(TestSink.probe[String])
          .request(10)
          .expectNext("a", "bb", "ccc", "dddd", "eeeee", "ffffff", "aaaaaaa", "bbbbbbbb", "ccccccccc", "dddddddddd")
          .expectComplete()
      }

      "... with only single element in loopSource" in {
        val s1 = Source(1 to 10)
        val loopSource = Source.single("a")
        val flow = Flow[(Int, String)] map { case (i: Int, s: String) => (s * i, s) }
        val loop = s1.zipWithLoop(1, loopSource)(flow)
        loop.runWith(TestSink.probe[String])
          .request(5)
          .expectNext("a", "aa", "aaa", "aaaa", "aaaaa")
          .request(5)
          .expectNext("aaaaaa", "aaaaaaa", "aaaaaaaa", "aaaaaaaaa", "aaaaaaaaaa")
          .expectComplete()
      }

      "... with empty loopSource" in {
        val s1 = Source(1 to 10)
        val loopSource = Source.empty[String]
        val flow = Flow[(Int, String)] map { case (i: Int, s: String) => (s * i, s) }
        val loop = s1.zipWithLoop(1, loopSource)(flow)
        loop.runWith(TestSink.probe[String])
          .request(5)
          .expectNoMsg(1.second)
      }
    }

  }

}