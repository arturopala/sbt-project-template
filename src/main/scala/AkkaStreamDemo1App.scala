import scala.concurrent.{ Future, Promise, Await }
import scala.util.{ Try, Success, Failure }
import scala.util.control.NonFatal
import scala.concurrent.duration._
import akka.actor.{ ActorSystem, Cancellable }
import akka.event.Logging
import akka.stream._
import akka.stream.scaladsl._
import FlowGraph.Implicits._

object AkkaStreamDemo1App {

  def main(args: Array[String]): Unit = {

    val noOfThreads = args.lift(0).map(_.toInt).getOrElse(5)
    val noOfTasks = args.lift(1).map(_.toInt).getOrElse(100)
    val maxTaskDelay = args.lift(2).map(_.toInt).getOrElse(200)
    val taskThrottle = args.lift(3).map(_.toInt).getOrElse(10)

    println(s"Running demo1 with noOfThreads=$noOfThreads, noOfTasks=$noOfTasks, maxTaskDelay=$maxTaskDelay, taskThrottle=$taskThrottle")

    implicit val system = akka.actor.ActorSystem("demo")
    implicit val materializer = akka.stream.ActorMaterializer()
    implicit val log = Logging(system, "")

    import scala.concurrent.ExecutionContext.Implicits.global

    // simple domain definition
    case class Worker(name: String)
    case class Task[A](id: Int, run: Worker => A)

    type MyTask = Task[(Int, String)]

    // dumb task generator
    def taskGenerator(i: Int): MyTask = Task(i, (w: Worker) => {
      val delay = scala.util.Random.nextInt(maxTaskDelay)
      Thread.sleep(delay)
      (i, "Worker %1$s did task in %2$s ms".format(w.name, delay))
    })

    import MoreFlowOps._

    val tasks = Source(Stream.from(1).map(taskGenerator)).throttle(taskThrottle.millis)

    val workerNames = List("A", "B", "C", "D", "E", "F", "G")
    val workers = Source[Worker](workerNames.map(Worker(_)))

    val job = Flow[(MyTask, Worker)] map { case (t, w) => (t.run(w), w) }

    val executor = Flow[MyTask].zipWithLoop(noOfThreads, workers)(job)

    ///////////////////////////////////////
    // HERE we really run our processing //
    ///////////////////////////////////////
    val future = tasks
      .take(noOfTasks)
      .via(executor)
      .log("done").withAttributes(Attributes.logLevels(onElement = Logging.InfoLevel))
      .runWith(Sink.ignore)

    // shutdown when done
    future.onComplete { _ ⇒
      println("Done.")
      system.shutdown()
    }
  }
}