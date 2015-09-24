import scala.concurrent.{ Future, Promise, Await }
import scala.util.{ Try, Success, Failure }
import scala.util.control.NonFatal
import scala.concurrent.duration._
import akka.actor.{ ActorSystem, Cancellable }
import akka.event.Logging
import akka.stream._
import akka.stream.scaladsl._
import FlowGraph.Implicits._

/**
 * Akka Stream demo app showing an example of complex processing graph setup and running.
 */
object AkkaStreamDemo1App {

  def main(args: Array[String]): Unit = new WithAkkaStream {

    def parseInt(i: Int, default: Int): Int = args.lift(i).map(_.toInt).getOrElse(default)

    val noOfThreads = parseInt(0, 5)
    val noOfTasks = parseInt(1, 100)
    val maxTaskDelay = parseInt(2, 200)
    val taskThrottle = parseInt(3, 10)

    import Task._
    import MoreFlowOps._

    val tasks: Source[MyTask, Unit] = Source(Stream.from(1).map(taskGenerator(maxTaskDelay))).throttle(taskThrottle.millis, 1.minute)

    val workerNames = List("A", "B", "C", "D", "E", "F", "G")
    val workers = Source[Worker](workerNames.map(Worker(_)))

    val job = Flow[(MyTask, Worker)] map { case (t, w) => (t.run(w), w) }

    val executor = Flow[MyTask].zipWithLoop(noOfThreads, workers)(job)

    val process = tasks
      .take(noOfTasks)
      .via(executor)
      .log("done").withAttributes(Attributes.logLevels(onElement = Logging.InfoLevel))

    ///////////////////////////////////////
    // HERE we really run our processing //
    ///////////////////////////////////////
    println(s"Running demo1 with noOfThreads=$noOfThreads, noOfTasks=$noOfTasks, maxTaskDelay=$maxTaskDelay, taskThrottle=$taskThrottle")
    val future = process.runWith(Sink.ignore)

    await(future)
  }
}

/**
 * Example task and worker domain definition
 */
object Task {

  case class Worker(name: String)
  case class Task[A](id: Int, run: Worker => A)

  type MyTask = Task[(Int, String)]

  // dumb task generator
  def taskGenerator(maxDelay: Int)(i: Int): MyTask = Task(i, (w: Worker) => {
    val delay = scala.util.Random.nextInt(maxDelay)
    Thread.sleep(delay)
    (i, "Worker %1$s did task in %2$s ms".format(w.name, delay))
  })

}