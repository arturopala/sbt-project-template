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

    import FlowGraph.Implicits._

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

      val g6 = FlowGraph.closed() { implicit builder =>
        val A: Outlet[Int] = builder.add(Source.single(0))
        val B: UniformFanOutShape[Int, Int] = builder.add(Broadcast[Int](2))
        val C: UniformFanInShape[Int, Int] = builder.add(Merge[Int](2))
        val D: FlowShape[Int, Int] = builder.add(Flow[Int].map(_ + 1))
        val E: UniformFanOutShape[Int, Int] = builder.add(Balance[Int](2))
        val F: UniformFanInShape[Int, Int] = builder.add(Merge[Int](2))
        val G: Inlet[Any] = builder.add(Sink.foreach(println))

        C <~ F
        A ~> B ~> C ~> F
        B ~> D ~> E ~> F
        E ~> G
      }

      val g7 = FlowGraph.closed() { implicit builder =>
        val B = builder.add(Broadcast[Int](2))
        val C = builder.add(Merge[Int](2))
        val E = builder.add(Balance[Int](2))
        val F = builder.add(Merge[Int](2))

        Source.single(0) ~> B.in; B.out(0) ~> C.in(1); C.out ~> F.in(0)
        C.in(0) <~ F.out

        B.out(1).map(_ + 1) ~> E.in; E.out(0) ~> F.in(1)
        E.out(1) ~> Sink.foreach(println)
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

      val partial = FlowGraph.partial() { implicit builder =>
        val B = builder.add(Broadcast[Int](2))
        val C = builder.add(Merge[Int](2))
        val E = builder.add(Balance[Int](2))
        val F = builder.add(Merge[Int](2))

        C <~ F
        B ~> C ~> F
        B ~> Flow[Int].map(_ + 1) ~> E ~> F
        FlowShape(B.in, E.out(1))
      }.named("partial")

      Source.single(0).via(partial).to(Sink.ignore)

      // Convert the partial graph of FlowShape to a Flow to get
      // access to the fluid DSL (for example to be able to call .filter())
      val flow = Flow.wrap(partial)

      // Simple way to create a graph backed Source
      val source = Source() { implicit builder =>
        val merge = builder.add(Merge[Int](2))
        Source.single(0) ~> merge
        Source(List(2, 3, 4)) ~> merge

        // Exposing exactly one output port
        merge.out
      }

      // Building a Sink with a nested Flow, using the fluid DSL
      val sink = {
        val nestedFlow = Flow[Int].map(_ * 2).drop(10).named("nestedFlow")
        nestedFlow.to(Sink.head)
      }

      // Putting all together
      val closed = source.via(flow.filter(_ > 1)).to(sink)

      val closed1 = Source.single(0).to(Sink.foreach(println))

      val closed2 = FlowGraph.closed() { implicit builder =>
        val embeddedClosed: ClosedShape = builder.add(closed1)
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

  object customstage {

    import akka.stream.stage._

    class Map[A, B](f: A => B) extends PushStage[A, B] {
      override def onPush(elem: A, ctx: Context[B]): SyncDirective =
        ctx.push(f(elem))
    }

    class Filter[A](p: A => Boolean) extends PushStage[A, A] {
      override def onPush(elem: A, ctx: Context[A]): SyncDirective =
        if (p(elem)) ctx.push(elem)
        else ctx.pull()
    }

    class Duplicator[A]() extends PushPullStage[A, A] {
      private var lastElem: A = _
      private var oneLeft = false

      override def onPush(elem: A, ctx: Context[A]): SyncDirective = {
        lastElem = elem
        oneLeft = true
        ctx.push(elem)
      }

      override def onPull(ctx: Context[A]): SyncDirective =
        if (!ctx.isFinishing) {
          // the main pulling logic is below as it is demonstrated on the illustration
          if (oneLeft) {
            oneLeft = false
            ctx.push(lastElem)
          }
          else
            ctx.pull()
        }
        else {
          // If we need to emit a final element after the upstream
          // finished
          if (oneLeft) ctx.pushAndFinish(lastElem)
          else ctx.finish()
        }

      override def onUpstreamFinish(ctx: Context[A]): TerminationDirective =
        ctx.absorbTermination()

    }

    class Duplicator2[A]() extends StatefulStage[A, A] {
      override def initial: StageState[A, A] = new StageState[A, A] {
        override def onPush(elem: A, ctx: Context[A]): SyncDirective =
          emit(List(elem, elem).iterator, ctx)
      }
    }

    val resultFuture = Source(1 to 10)
      .transform(() => new Filter(_ % 2 == 0))
      .transform(() => new Duplicator())
      .transform(() => new Map(_ / 2))
      .runWith(Sink.ignore)

    class Buffer2[T]() extends DetachedStage[T, T] {
      private var buf = Vector.empty[T]
      private var capacity = 2

      private def isFull = capacity == 0
      private def isEmpty = capacity == 2

      private def dequeue(): T = {
        capacity += 1
        val next = buf.head
        buf = buf.tail
        next
      }

      private def enqueue(elem: T) = {
        capacity -= 1
        buf = buf :+ elem
      }

      override def onPull(ctx: DetachedContext[T]): DownstreamDirective = {
        if (isEmpty) {
          if (ctx.isFinishing) ctx.finish() // No more elements will arrive
          else ctx.holdDownstream() // waiting until new elements
        }
        else {
          val next = dequeue()
          if (ctx.isHoldingUpstream) ctx.pushAndPull(next) // release upstream
          else ctx.push(next)
        }
      }

      override def onPush(elem: T, ctx: DetachedContext[T]): UpstreamDirective = {
        enqueue(elem)
        if (isFull) ctx.holdUpstream() // Queue is now full, wait until new empty slot
        else {
          if (ctx.isHoldingDownstream) ctx.pushAndPull(dequeue()) // Release downstream
          else ctx.pull()
        }
      }

      override def onUpstreamFinish(ctx: DetachedContext[T]): TerminationDirective = {
        if (!isEmpty) ctx.absorbTermination() // still need to flush from buffer
        else ctx.finish() // already empty, finishing
      }
    }

  }

  object customjunction {

    object fleximerge {

      import akka.stream.FanInShape._

      class ZipPorts[A, B](_init: Init[(A, B)] = Name("Zip")) extends FanInShape[(A, B)](_init) {
        val left = newInlet[A]("left")
        val right = newInlet[B]("right")
        protected override def construct(i: Init[(A, B)]) = new ZipPorts(i)
      }

      class Zip[A, B] extends FlexiMerge[(A, B), ZipPorts[A, B]](new ZipPorts, Attributes.name("Zip2State")) {

        import FlexiMerge._

        override def createMergeLogic(p: PortT) = new MergeLogic[(A, B)] {
          var lastInA: A = _

          val readA: State[A] = State[A](Read(p.left)) { (ctx, input, element) =>
            lastInA = element
            readB
          }

          val readB: State[B] = State[B](Read(p.right)) { (ctx, input, element) =>
            ctx.emit((lastInA, element))
            readA
          }

          override def initialState: State[_] = readA

          override def initialCompletionHandling = eagerClose
        }
      }

      class ZipAll[A, B] extends FlexiMerge[(A, B), ZipPorts[A, B]](new ZipPorts, Attributes.name("Zip1State")) {

        import FlexiMerge._

        override def createMergeLogic(p: PortT) = new MergeLogic[(A, B)] {
          override def initialState = State(ReadAll(p.left, p.right)) { (ctx, _, inputs) =>
            val a = inputs(p.left)
            val b = inputs(p.right)
            ctx.emit((a, b))
            SameState
          }

          override def initialCompletionHandling = eagerClose
        }
      }

      FlowGraph.closed(Sink.head[(Int, String)]) { implicit b =>
        o =>
          import FlowGraph.Implicits._

          val zip = b.add(new Zip[Int, String])

          Source.single(1) ~> zip.left
          Source.single("1") ~> zip.right
          zip.out ~> o.inlet
      }
    }

    object flexiroute {

      import FanOutShape._

      class UnzipShape[A, B](_init: Init[(A, B)] = Name[(A, B)]("Unzip"))
          extends FanOutShape[(A, B)](_init) {
        val outA = newOutlet[A]("outA")
        val outB = newOutlet[B]("outB")
        protected override def construct(i: Init[(A, B)]) = new UnzipShape(i)
      }

      class Unzip[A, B] extends FlexiRoute[(A, B), UnzipShape[A, B]](
        new UnzipShape, Attributes.name("Unzip")) {
        import FlexiRoute._

        override def createRouteLogic(p: PortT) = new RouteLogic[(A, B)] {
          override def initialState =
            State[Any](DemandFromAll(p.outA, p.outB)) {
              (ctx, _, element) =>
                val (a, b) = element
                ctx.emit(p.outA)(a)
                ctx.emit(p.outB)(b)
                SameState
            }

          override def initialCompletionHandling = eagerClose
        }
      }
    }

  }

}