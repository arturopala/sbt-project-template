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
    "provide source, flow and sink components" which {

      "allows to build and run simple workflow" in {

        // simple domain definition
        case class Worker(name: String)
        case class Task[A](id: Int, run: Worker => A)

        // dumb task generator
        def taskGenerator(i: Int): Task[(Int, String)] = Task(i, (w: Worker) => {
          val delay = scala.util.Random.nextInt(80) + 20
          Thread.sleep(delay)
          (i, w.name+" "+delay+"ms ["+Thread.currentThread.getId+"]")
        })

        trait Tick
        object Tick extends Tick {
          def onTick[A, M](source: Source[A, M], clock: Source[Tick, Cancellable]): Source[A, M] = source.via((Flow() {
            implicit b =>
              val merge = b.add(Zip[A, Tick])
              clock ~> merge.in1
              (merge.in0, merge.out)
          })).map(_._1)

          implicit class SourceTickOps[A, M](source: Source[A, M]) {
            def onTick(clock: Source[Tick, Cancellable]): Source[A, M] = Tick.onTick(source, clock)
          }
        }
        import Tick.SourceTickOps
        val clock: Source[Tick, Cancellable] = Source(0.millis, 10.millis, Tick)

        // tasks source
        val tasks = Source(Stream.from(1).map(taskGenerator)).onTick(clock)

        // taks runner definition
        def taskRunner[A](threads: Int, names: scala.collection.immutable.Iterable[String]) = FlowGraph.partial() {
          implicit b =>

            // initial pool of workers
            val initialWorkers = Source[Worker](names.map(Worker(_)))

            // processing graph components definition
            val workers = b.add(MergePreferred[Worker](1))
            val zipper = b.add(Zip[Worker, Task[A]])
            val balancer = b.add(Balance[(Worker, Task[A])](threads))
            val merger = b.add(Merge[(Worker, A)](threads))
            val unzipper = b.add(Unzip[Worker, A])

            // executor flow does real bussines job
            val executor = Flow[(Worker, Task[A])] map { case (w, t) => (w, t.run(w)) }

            // wiring components
            initialWorkers ~> workers.in(0)
            workers.out ~> zipper.in0
            zipper.out ~> balancer.in
            for (i <- 0 until threads) {
              balancer.out(i) ~> executor ~> merger.in(i)
            }
            merger.out ~> unzipper.in
            unzipper.out0 ~> workers.preferred

            // we returns single inlet and single outlet to wrap later our processing graph as a flow
            FlowShape(zipper.in1, unzipper.out1)
        }.named("task-runner")

        val noOfThreads = 5
        val noOfTasks = 100
        val workerNames = List("Adam", "Marek", "Andrzej", "Krzysztof", "Wojciech", "Jerzy", "Stanisław")

        // taks runner instance
        val runner = Flow.wrap(taskRunner[(Int, String)](noOfThreads, workerNames)).map(e => { println(e); e })

        // HERE we really run our processing
        val future = tasks.take(noOfTasks).via(runner).runWith(Sink.ignore)

        // waiting for end of processing
        Await.result(future, 60.seconds)

      }
    }
  }

}