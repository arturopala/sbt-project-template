import org.scalatest.{ WordSpecLike, Matchers }
import org.scalatest.prop.PropertyChecks
import org.scalatest.junit.JUnitRunner
import org.scalacheck._
import org.scalatest.matchers._
import org.scalatest.concurrent.ScalaFutures._
import scala.concurrent.duration._

class AkkaStreamExamplesSpec extends WordSpecLike with Matchers with PropertyChecks {

  implicit override val generatorDrivenConfig = PropertyCheckConfig(minSize = 1, maxSize = 100, minSuccessful = 100, workers = 5)

  import scala.concurrent.{ Future, Await }
  import scala.util.{ Try, Success, Failure }
  import scala.util.control.NonFatal
  import akka.actor.{ ActorSystem, Cancellable }
  import akka.stream._
  import akka.stream.scaladsl._
  import FlowGraph.Implicits._

  implicit val system = akka.actor.ActorSystem()
  implicit val materializer = akka.stream.ActorMaterializer()

  "An Akka Stream" must {

    "be easily run and tested" which {

      "can be done using just ordinary source" in {
        //Testing a custom sink can be as simple as attaching a source that emits elements from a predefined 
        //collection, running a constructed test flow and asserting on the results that sink produced. 
        //Here is an example of a test for a sink:
        val sinkUnderTest = Flow[Int].map(_ * 2).toMat(Sink.fold(0)(_ + _))(Keep.right)
        val future = Source(1 to 4).runWith(sinkUnderTest)
        val result = Await.result(future, 100.millis)
        assert(result == 20)
      }

      "or ordinary sink" in {
        val sourceUnderTest = Source.repeat(1).map(_ * 2)
        val future = sourceUnderTest.grouped(10).runWith(Sink.head)
        val result = Await.result(future, 100.millis)
        assert(result == Seq.fill(10)(2))
      }

      "or both source and sink" in {
        val flowUnderTest = Flow[Int].takeWhile(_ < 5)
        val future = Source(1 to 10).via(flowUnderTest).runWith(Sink.fold(Seq.empty[Int])(_ :+ _))
        val result = Await.result(future, 100.millis)
        assert(result == (1 to 4))
      }

      "but also using Akka TestKit" in {
        import system.dispatcher
        import akka.testkit.TestProbe
        import akka.pattern.pipe

        val sourceUnderTest = Source(1 to 4).grouped(2)
        val probe = TestProbe()
        sourceUnderTest.grouped(2).runWith(Sink.head).pipeTo(probe.ref)
        probe.expectMsg(100.millis, Seq(Seq(1, 2), Seq(3, 4)))
      }

      "we can also use a Sink.actorRef that sends all incoming elements to the given ActorRef" in {
        import akka.testkit.TestProbe
        case object Tick
        val sourceUnderTest = Source(0.seconds, 200.millis, Tick)

        val probe = TestProbe()
        val cancellable = sourceUnderTest.to(Sink.actorRef(probe.ref, "completed")).run()

        probe.expectMsg(1.second, Tick)
        probe.expectNoMsg(100.millis)
        probe.expectMsg(200.millis, Tick)
        cancellable.cancel()
        probe.expectMsg(200.millis, "completed")
      }

      "we can use Source.actorRef too and have full control over elements to be sent." in {
        val sinkUnderTest = Flow[Int].map(_.toString).toMat(Sink.fold("")(_ + _))(Keep.right)

        val (ref, future) = Source.actorRef(8, OverflowStrategy.fail).toMat(sinkUnderTest)(Keep.both).run()

        ref ! 1
        ref ! 2
        ref ! 3
        ref ! akka.actor.Status.Success("done")

        val result = Await.result(future, 100.millis)
        assert(result == "123")
      }

      "akka-stream-testkit module that provides tools specifically for writing stream tests like TestSink" in {
        import akka.stream.testkit.scaladsl.TestSink
        val sourceUnderTest = Source(1 to 4).filter(_ % 2 == 0).map(_ * 2)
        sourceUnderTest
          .runWith(TestSink.probe[Int])
          .request(2)
          .expectNext(4, 8)
          .expectComplete()
      }

      "or TestSource" in {
        import akka.stream.testkit.scaladsl.TestSource
        val sinkUnderTest = Sink.cancelled

        TestSource.probe[Int]
          .toMat(sinkUnderTest)(Keep.left)
          .run()
          .expectCancellation()
      }

      "TestSource allows to inject exceptions and test sink behaviour on error conditions" in {
        import akka.stream.testkit.scaladsl.TestSource
        val sinkUnderTest = Sink.head[Int]

        val (probe, future) = TestSource.probe[Int]
          .toMat(sinkUnderTest)(Keep.both)
          .run()
        probe.sendError(new Exception("boom"))

        Await.ready(future, 100.millis)
        val Failure(exception) = future.value.get
        assert(exception.getMessage == "boom")
      }

      "Test source and sink can be used together in combination when testing flows" in {
        import akka.stream.testkit.scaladsl._
        import scala.concurrent.ExecutionContext.Implicits.global

        val flowUnderTest = Flow[Int].mapAsyncUnordered(2) { sleep =>
          akka.pattern.after(10.millis * sleep, using = system.scheduler)(Future.successful(sleep))
        }

        val (pub, sub) = TestSource.probe[Int]
          .via(flowUnderTest)
          .toMat(TestSink.probe[Int])(Keep.both)
          .run()

        sub.request(n = 3)
        pub.sendNext(3)
        pub.sendNext(2)
        pub.sendNext(1)
        sub.expectNextUnordered(1, 2, 3)

        pub.sendError(new Exception("Power surge in the linear subroutine C-47!"))
        val ex = sub.expectError
        assert(ex.getMessage.contains("C-47"))
      }

    }

    "provide source, flow and sink components" which {

      "allows to build and run custom workflow" in {

      }
    }
  }

}