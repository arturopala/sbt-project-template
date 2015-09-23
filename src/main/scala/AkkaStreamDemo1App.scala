import scala.concurrent.{ Future, Promise, Await }
import scala.util.{ Try, Success, Failure }
import scala.util.control.NonFatal
import scala.concurrent.duration._
import akka.actor.{ ActorSystem, Cancellable }
import akka.event.Logging
import akka.stream._
import akka.stream.scaladsl._
import FlowGraph.Implicits._

object AkkaStreamDemo1App extends App {

  implicit val system = akka.actor.ActorSystem()
  implicit val materializer = akka.stream.ActorMaterializer()
  implicit val log = Logging(system, "demo1")

  import scala.concurrent.ExecutionContext.Implicits.global

  // simple domain definition
  case class Worker(name: String)
  case class Task[A](id: Int, run: Worker => A)

  // dumb task generator
  def taskGenerator(i: Int): Task[(Int, String)] = Task(i, (w: Worker) => {
    val delay = scala.util.Random.nextInt(80) + 20
    Thread.sleep(delay)
    (i, "Worker %2$s did task %1$03d in %3$ 3d ms on thread %4$s".format(i, w.name, delay, Thread.currentThread.getId))
  })

  // clock
  import MoreFlowOps._

  //////////////////
  // tasks source //
  //////////////////
  val tasks = Source(Stream.from(1).map(taskGenerator)).throttle(20.millis)

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
  val workerNames = List("A", "B", "C", "D", "E", "F", "G")

  println(s"Running demo1 using ${workerNames.size} workers for $noOfTasks tasks on $noOfThreads threads.")

  // taks runner instance
  val runner = Flow.wrap(taskRunner[(Int, String)](noOfThreads, workerNames)).map(e => { println(e._2); e })

  ///////////////////////////////////////
  // HERE we really run our processing //
  ///////////////////////////////////////
  val future = tasks.take(noOfTasks).via(runner).runWith(Sink.ignore)

  // shutdown when done
  future.onComplete { _ ⇒
    println("Done.")
    system.shutdown()
  }
}