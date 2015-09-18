object AkkaStreamExamples {

  import scala.concurrent.{ Future, Promise }
  import scala.util.{ Try, Success, Failure }
  import scala.util.control.NonFatal
  import akka.actor.ActorSystem
  import akka.stream._
  import akka.stream.scaladsl._
  import akka.actor.Cancellable
  import akka.util.ByteString
  import java.nio.ByteOrder

  implicit val system = akka.actor.ActorSystem()
  implicit val materializer = akka.stream.ActorMaterializer()

  import scala.concurrent.ExecutionContext.Implicits.global

  object basic {

    val source = Source(1 to 10)
    val sink = Sink.fold[Int, Int](0)(_ + _)

    // connect the Source to the Sink, obtaining a RunnableGraph
    val runnable: RunnableGraph[Future[Int]] = source.toMat(sink)(Keep.right)

    // materialize the flow and get the value of the FoldSink
    val sum: Future[Int] = runnable.run()

    // materialize the flow, getting the Sinks materialized value
    val sum2: Future[Int] = source.runWith(sink)

    val zeroes = source.map(_ => 0) // returns new Source[Int], with `map()` appended
    zeroes.runWith(Sink.fold(0)(_ + _)) // 0

    val runnable2: RunnableGraph[Future[Int]] = Source(1 to 10).toMat(sink)(Keep.right)

    Source(1 to 6).via(Flow[Int].map(_ * 2)).to(Sink.foreach(println(_)))

    // Starting from a Source
    val source2 = Source(1 to 6).map(_ * 2)
    source2.to(Sink.foreach(println(_)))

    // Starting from a Sink
    val sink2: Sink[Int, Unit] = Flow[Int].map(_ * 2).to(Sink.foreach(println(_)))
    Source(1 to 6).to(sink2)

  }

  object graph {

    object closed {

      val g = FlowGraph.closed() { builder: FlowGraph.Builder[Unit] =>
        val in = Source(1 to 10)
        val out = Sink.ignore

        val broadcast = builder.add(Broadcast[Int](2))
        val merge = builder.add(Merge[Int](2))

        val f1 = Flow[Int].map(_ + 10)
        val f3 = Flow[Int].map(_.toString)
        val f2 = Flow[Int].map(_ + 20)

        builder.addEdge(builder.add(in), broadcast.in)
        builder.addEdge(broadcast.out(0), f1, merge.in(0))
        builder.addEdge(broadcast.out(1), f2, merge.in(1))
        builder.addEdge(merge.out, f3, builder.add(out))
      }

      val topHeadSink = Sink.head[Int]
      val bottomHeadSink = Sink.head[Int]
      val sharedDoubler = Flow[Int].map(_ * 2)

      val g2 = FlowGraph.closed(topHeadSink, bottomHeadSink)((_, _)) { implicit builder =>
        (topHS, bottomHS) =>
          import FlowGraph.Implicits._
          val broadcast = builder.add(Broadcast[Int](2))
          Source.single(1) ~> broadcast.in

          broadcast.out(0) ~> sharedDoubler ~> topHS.inlet
          broadcast.out(1) ~> sharedDoubler ~> bottomHS.inlet
      }

      val pickMaxOfThree = FlowGraph.partial() { implicit b =>
        import FlowGraph.Implicits._

        val zip1 = b.add(ZipWith[Int, Int, Int](math.max _))
        val zip2 = b.add(ZipWith[Int, Int, Int](math.max _))
        zip1.out ~> zip2.in0

        UniformFanInShape(zip2.out, zip1.in0, zip1.in1, zip2.in1)
      }

      val resultSink = Sink.head[Int]

      val g3 = FlowGraph.closed(resultSink) { implicit b =>
        sink =>
          import FlowGraph.Implicits._

          // importing the partial graph will return its shape (inlets & outlets)
          val pm3 = b.add(pickMaxOfThree)

          Source.single(1) ~> pm3.in(0)
          Source.single(2) ~> pm3.in(1)
          Source.single(3) ~> pm3.in(2)
          pm3.out ~> sink.inlet
      }

      val source = Source(1 to 10)

      val g4 = FlowGraph.closed() { implicit b =>
        import FlowGraph.Implicits._

        val merge = b.add(Merge[Int](2))
        val bcast = b.add(Broadcast[Int](2))

        source ~> merge ~> Flow[Int].map { s => println(s); s } ~> bcast ~> Sink.ignore
        merge <~ Flow[Int].buffer(10, OverflowStrategy.dropHead) <~ bcast
      }

      val g5 = FlowGraph.closed() { implicit b =>
        import FlowGraph.Implicits._

        val zip = b.add(ZipWith((left: Int, right: Int) => left))
        val bcast = b.add(Broadcast[Int](2))
        val concat = b.add(Concat[Int]())
        val start = Source.single(0)

        source ~> zip.in0
        zip.out.map { s => println(s); s } ~> bcast ~> Sink.ignore
        zip.in1 <~ concat <~ start
        concat <~ bcast
      }

    }

    object partial {

      val pairs = Source() { implicit b =>
        import FlowGraph.Implicits._

        // prepare graph elements
        val zip = b.add(Zip[Int, Int]())
        def ints = Source(() => Iterator.from(1))

        // connect the graph
        ints.filter(_ % 2 != 0) ~> zip.in0
        ints.filter(_ % 2 == 0) ~> zip.in1

        // expose port
        zip.out
      }

      val pairUpWithToString = Flow() { implicit b =>
        import FlowGraph.Implicits._

        // prepare graph elements
        val broadcast = b.add(Broadcast[Int](2))
        val zip = b.add(Zip[Int, String]())

        // connect the graph
        broadcast.out(0).map(identity) ~> zip.in0
        broadcast.out(1).map(_.toString) ~> zip.in1

        // expose ports
        (broadcast.in, zip.out)
      }

    }

    object customshape {

      import scala.collection._

      // A shape represents the input and output ports of a reusable
      // processing module
      case class PriorityWorkerPoolShape[In, Out](
          jobsIn: Inlet[In],
          priorityJobsIn: Inlet[In],
          resultsOut: Outlet[Out]) extends Shape {

        // It is important to provide the list of all input and output
        // ports with a stable order. Duplicates are not allowed.
        override val inlets: immutable.Seq[Inlet[_]] = jobsIn :: priorityJobsIn :: Nil
        override val outlets: immutable.Seq[Outlet[_]] = resultsOut :: Nil

        // A Shape must be able to create a copy of itself. Basically
        // it means a new instance with copies of the ports
        override def deepCopy() = PriorityWorkerPoolShape(
          jobsIn.carbonCopy(),
          priorityJobsIn.carbonCopy(),
          resultsOut.carbonCopy())

        // A Shape must also be able to create itself from existing ports
        override def copyFromPorts(
          inlets: immutable.Seq[Inlet[_]],
          outlets: immutable.Seq[Outlet[_]]) = {
          assert(inlets.size == this.inlets.size)
          assert(outlets.size == this.outlets.size)
          // This is why order matters when overriding inlets and outlets
          PriorityWorkerPoolShape(inlets(0), inlets(1), outlets(0))
        }
      }

      //Since our shape has two input ports and one output port, 
      //we can just use the FanInShape DSL to define our custom shape:

      import FanInShape.Name
      import FanInShape.Init

      class PriorityWorkerPoolShape2[In, Out](_init: Init[Out] = Name("PriorityWorkerPool"))
          extends FanInShape[Out](_init) {
        protected override def construct(i: Init[Out]) = new PriorityWorkerPoolShape2(i)

        val jobsIn = newInlet[In]("jobsIn")
        val priorityJobsIn = newInlet[In]("priorityJobsIn")
        // Outlet[Out] with name "out" is automatically created
      }

      //Now that we have a Shape we can wire up a Graph that represents our worker pool. 
      //First, we will merge incoming normal and priority jobs using MergePreferred, 
      //then we will send the jobs to a Balance junction which will fan-out to a configurable 
      //number of workers (flows), finally we merge all these results together and send them out 
      //through our only output port. This is expressed by the following code:

      object PriorityWorkerPool {
        def apply[In, Out](
          worker: Flow[In, Out, Any],
          workerCount: Int): Graph[PriorityWorkerPoolShape[In, Out], Unit] = {

          FlowGraph.partial() { implicit b ⇒
            import FlowGraph.Implicits._

            val priorityMerge = b.add(MergePreferred[In](1))
            val balance = b.add(Balance[In](workerCount))
            val resultsMerge = b.add(Merge[Out](workerCount))

            // After merging priority and ordinary jobs, we feed them to the balancer
            priorityMerge ~> balance

            // Wire up each of the outputs of the balancer to a worker flow
            // then merge them back
            for (i <- 0 until workerCount)
              balance.out(i) ~> worker ~> resultsMerge.in(i)

            // We now expose the input ports of the priorityMerge and the output
            // of the resultsMerge as our PriorityWorkerPool ports
            // -- all neatly wrapped in our domain specific Shape
            PriorityWorkerPoolShape(
              jobsIn = priorityMerge.in(0),
              priorityJobsIn = priorityMerge.preferred,
              resultsOut = resultsMerge.out)
          }

        }

      }

      val worker1 = Flow[String].map("step 1 "+_)
      val worker2 = Flow[String].map("step 2 "+_)

      FlowGraph.closed() { implicit b =>
        import FlowGraph.Implicits._

        val priorityPool1 = b.add(PriorityWorkerPool(worker1, 4))
        val priorityPool2 = b.add(PriorityWorkerPool(worker2, 2))

        Source(1 to 100).map("job: "+_) ~> priorityPool1.jobsIn
        Source(1 to 100).map("priority job: "+_) ~> priorityPool1.priorityJobsIn

        priorityPool1.resultsOut ~> priorityPool2.jobsIn
        Source(1 to 100).map("one-step, priority "+_) ~> priorityPool2.priorityJobsIn

        priorityPool2.resultsOut ~> Sink.foreach(println)
      }

    }

  }

  object bidi {

    trait Message
    case class Ping(id: Int) extends Message
    case class Pong(id: Int) extends Message

    def toBytes(msg: Message): ByteString = {
      implicit val order = ByteOrder.LITTLE_ENDIAN
      msg match {
        case Ping(id) => ByteString.newBuilder.putByte(1).putInt(id).result()
        case Pong(id) => ByteString.newBuilder.putByte(2).putInt(id).result()
      }
    }

    def fromBytes(bytes: ByteString): Message = {
      implicit val order = ByteOrder.LITTLE_ENDIAN
      val it = bytes.iterator
      it.getByte match {
        case 1     => Ping(it.getInt)
        case 2     => Pong(it.getInt)
        case other => throw new RuntimeException(s"parse error: expected 1|2 got $other")
      }
    }

    val codecVerbose = BidiFlow() { b =>
      // construct and add the top flow, going outbound
      val outbound = b.add(Flow[Message].map(toBytes))
      // construct and add the bottom flow, going inbound
      val inbound = b.add(Flow[ByteString].map(fromBytes))
      // fuse them together into a BidiShape
      BidiShape(outbound, inbound)
    }

    // this is the same as the above
    val codec = BidiFlow(toBytes _, fromBytes _)

  }

  object pushpull {

    import akka.stream.stage._

    val framing = BidiFlow() { b =>
      implicit val order = ByteOrder.LITTLE_ENDIAN

      def addLengthHeader(bytes: ByteString) = {
        val len = bytes.length
        ByteString.newBuilder.putInt(len).append(bytes).result()
      }

      class FrameParser extends PushPullStage[ByteString, ByteString] {
        // this holds the received but not yet parsed bytes
        var stash = ByteString.empty
        // this holds the current message length or -1 if at a boundary
        var needed = -1

        override def onPush(bytes: ByteString, ctx: Context[ByteString]) = {
          stash ++= bytes
          run(ctx)
        }
        override def onPull(ctx: Context[ByteString]) = run(ctx)
        override def onUpstreamFinish(ctx: Context[ByteString]) =
          if (stash.isEmpty) ctx.finish()
          else ctx.absorbTermination() // we still have bytes to emit

        private def run(ctx: Context[ByteString]): SyncDirective =
          if (needed == -1) {
            // are we at a boundary? then figure out next length
            if (stash.length < 4) pullOrFinish(ctx)
            else {
              needed = stash.iterator.getInt
              stash = stash.drop(4)
              run(ctx) // cycle back to possibly already emit the next chunk
            }
          }
          else if (stash.length < needed) {
            // we are in the middle of a message, need more bytes
            pullOrFinish(ctx)
          }
          else {
            // we have enough to emit at least one message, so do it
            val emit = stash.take(needed)
            stash = stash.drop(needed)
            needed = -1
            ctx.push(emit)
          }

        /*
     * After having called absorbTermination() we cannot pull any more, so if we need
     * more data we will just have to give up.
     */
        private def pullOrFinish(ctx: Context[ByteString]) = if (ctx.isFinishing) ctx.finish() else ctx.pull()
      }

      val outbound = b.add(Flow[ByteString].map(addLengthHeader))
      val inbound = b.add(Flow[ByteString].transform(() => new FrameParser))
      BidiShape(outbound, inbound)
    }

    import bidi._

    //With these implementations we can build a protocol stack
    val stack = codec.atop(framing)
    // test it by plugging it into its own inverse and closing the right end
    val pingpong = Flow[Message].collect { case Ping(id) => Pong(id) }
    val flow = stack.atop(stack.reversed).join(pingpong)
    val result = Source((0 to 9).map(Ping)).via(flow).grouped(20).runWith(Sink.head)

  }

}