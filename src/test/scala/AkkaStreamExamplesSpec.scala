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
  import akka.actor.ActorSystem
  import akka.stream._
  import akka.stream.scaladsl._
  import FlowGraph.Implicits._

  implicit val system = akka.actor.ActorSystem()
  implicit val materializer = akka.stream.ActorMaterializer()

  "An Akka Stream" must {
    "provide source, flow and sink components" which {

      "allows to build and run simple workflow" in {

        case class Worker(name: String)
        case class Task[A](id: Int, run: Worker => A)

        val taskGenerator = (i: Int) => Task(i, (w: Worker) => {
          val delay = scala.util.Random.nextInt(80) + 20
          Thread.sleep(delay)
          (i, w.name+" "+delay+"ms ["+Thread.currentThread.getId+"]")
        })

        val tasks = Source(Stream.from(1).map(taskGenerator))

        def taskRunner[A](threads: Int, names: scala.collection.immutable.Iterable[String]): Flow[Task[A], A, Unit] = Flow() { implicit b =>

          val initialWorkers = Source[Worker](names.map(Worker(_)))
          val workers = b.add(MergePreferred[Worker](1))
          val zipper = b.add(Zip[Worker, Task[A]])
          val balancer = b.add(Balance[(Worker, Task[A])](threads))
          val merger = b.add(Merge[(Worker, A)](threads))
          val unzipper = b.add(Unzip[Worker, A])

          val executor = Flow[(Worker, Task[A])] map { case (w, t) => (w, t.run(w)) }

          initialWorkers ~> workers.in(0)
          workers.out ~> zipper.in0
          zipper.out ~> balancer.in
          for (i <- 0 until threads) {
            balancer.out(i) ~> executor ~> merger.in(i)
          }
          merger.out ~> unzipper.in
          unzipper.out0 ~> workers.preferred

          (zipper.in1, unzipper.out1)
        }

        val names = List("Adam", "Marek", "Andrzej", "Krzysztof", "Wojciech", "Jerzy", "Stanisław")
        val runner = taskRunner[(Int, String)](3, names)

        val future = tasks.take(100).via(runner.map(e => { println(e); e })).runWith(Sink.ignore)

        Await.result(future, 60.seconds)

      }
    }
  }

}