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
 * Common flow- and source related operations missing from standard library.
 */
object MoreFlowOps {

  implicit class SourceOps[A, Mat](source: Source[A, Mat]) {
    /** Join elements pulled from both sources using mapping function, keep left materialized value */
    def join[B, C](other: Source[B, _], f: (A, B) => C): Source[C, Mat] = JoinOps.join(source, other, f)(Keep.left).named("join")
    /** Join elements pulled from both sources using mapping function, combine materialized values */
    def joinMat[B, C, M1, M2](other: Source[B, M1], f: (A, B) => C)(combine: (Mat, M1) => M2): Source[C, M2] = JoinOps.join(source, other, f)(combine).named("joinMat")
    /** Pair elements pulled from both sources, keep left materialized value */
    def zip[B](other: Source[B, _]): Source[(A, B), Mat] = JoinOps.join(source, other, (a: A, b: B) => (a, b))(Keep.left).named("zip")
    /** Pair elements pulled from both sources, combine materialized values */
    def zipMat[B, M1, M2](other: Source[B, M1])(combine: (Mat, M1) => M2): Source[(A, B), M2] = JoinOps.join(source, other, (a: A, b: B) => (a, b))(combine).named("zipMat")
    /** Throttle source pushing elements each interval  */
    def throttle(interval: FiniteDuration): Source[A, Mat] = throttle(Clock(interval)).named("clock")
    /** Throttle source pushing one element for each one pulled from clock */
    def throttle[B](clock: Source[B, _]): Source[A, Mat] = JoinOps.join(source, clock, (a: A, b: B) => a)(Keep.left).named("throttle")
    /** Zip and push through flow elements pulled from both source and loopSource */
    def zipWithLoop[B, C](parallelism: Int, loopSource: Source[B, _]): (Flow[(A, B), (C, B), Mat]) => Source[C, Mat] = LoopOps.zipWithLoop[A, B, C, Mat](parallelism, loopSource, source)_
  }

  implicit class FlowOps[D, A, Mat](flow: Flow[D, A, Mat]) {
    /** Join elements pulled from both flows using mapping function, keep left materialized value */
    def join[B, C, E](other: Flow[C, B, _], f: (A, B) => E): Source[E, Mat] = JoinOps.join(flow, other, f)(Keep.left).named("join")
    /** Join elements pulled from both flows using mapping function, combine materialized values */
    def joinMat[B, C, E, M1, M2](other: Flow[C, B, M1], f: (A, B) => E)(combine: (Mat, M1) => M2): Source[E, M2] = JoinOps.join(flow, other, f)(combine).named("joinMat")
    /** Pair elements pulled from both flows, keep left materialized value */
    def zip[B, C](other: Flow[C, B, _]): Source[(A, B), Mat] = JoinOps.join(flow, other, (a: A, b: B) => (a, b))(Keep.left).named("zip")
    /** Pair elements pulled from both flows, combine materialized values */
    def zipMat[B, C, M1, M2](other: Flow[C, B, M1])(combine: (Mat, M1) => M2): Source[(A, B), M2] = JoinOps.join(flow, other, (a: A, b: B) => (a, b))(combine).named("zipMat")
    /** Zip and push through flow elements pulled from both base flow and loopSource */
    def zipWithLoop[B, C](parallelism: Int, loopSource: Source[B, _]): (Flow[(A, B), (C, B), Mat]) => Flow[D, C, Mat] = LoopOps.zipWithLoop[A, B, C, D, Mat](parallelism, loopSource, flow)_
  }

  object Clock {
    case object Tick
    def apply(interval: FiniteDuration): Source[Tick.type, Cancellable] = Source(0.millis, interval, Tick)
  }

  object JoinOps {

    def join[A, B, C, Mat, M1, M2](left: Source[A, M1], right: Source[B, M2], f: (A, B) => C)(combine: (M1, M2) => Mat): Source[C, Mat] = Source(left, right)(combine) {
      implicit b =>
        (left, right) =>
          val zipper = b.add(ZipWith[A, B, C](f))
          left ~> zipper.in0
          right ~> zipper.in1
          zipper.out
    }

    def join[A, B, C, D, E, Mat, M1, M2](left: Flow[A, B, M1], right: Flow[C, D, M2], f: (B, D) => E)(combine: (M1, M2) => Mat): Source[E, Mat] = Source(left, right)(combine) {
      implicit b =>
        (left, right) =>
          val zipper = b.add(ZipWith[B, D, E](f))
          left ~> zipper.in0
          right ~> zipper.in1
          zipper.out
    }

  }

  object LoopOps {

    /** Zip and push through flow elements pulled from both source and loopSource */
    def zipWithLoop[A, B, C, Mat](parallelism: Int, loopSource: Source[B, _], source: Source[A, Mat])(flow: Flow[(A, B), (C, B), Mat]): Source[C, Mat] = Source(source) {
      implicit b =>
        source =>

          assert(parallelism > 0)
          // junctions setup
          val loopGate = b.add(MergePreferred[B](1))
          val zipper = b.add(Zip[A, B])
          val fork = b.add(Balance[(A, B)](parallelism))
          val join = b.add(Merge[(C, B)](parallelism))
          val unzipper = b.add(Unzip[C, B])

          // components wiring
          source ~> zipper.in0
          loopSource ~> loopGate.in(0)
          loopGate.out ~> zipper.in1
          zipper.out ~> fork.in
          // multiple flow copies balanced
          for (i <- 0 until parallelism) {
            fork.out(i) ~> flow ~> join.in(i)
          }
          join.out ~> unzipper.in
          // back arc
          loopGate.preferred <~ unzipper.out1

          // we returns single outlet to wrap graph as a Source
          unzipper.out0
    }
      .named("sourceZipWithLoop")

    /** Zip and push through flow elements pulled from both base flow and loopSource */
    def zipWithLoop[A, B, C, D, Mat](parallelism: Int, loopSource: Source[B, _], base: Flow[D, A, Mat])(flow: Flow[(A, B), (C, B), Mat]): Flow[D, C, Mat] = Flow(base) {
      implicit b =>
        base =>

          assert(parallelism > 0)
          // junctions setup
          val loopGate = b.add(MergePreferred[B](1))
          val zipper = b.add(Zip[A, B])
          val fork = b.add(Balance[(A, B)](parallelism))
          val join = b.add(Merge[(C, B)](parallelism))
          val unzipper = b.add(Unzip[C, B])

          // components wiring
          base.outlet ~> zipper.in0
          loopSource ~> loopGate.in(0)
          loopGate.out ~> zipper.in1
          zipper.out ~> fork.in
          // multiple flow copies balanced
          for (i <- 0 until parallelism) {
            fork.out(i) ~> flow ~> join.in(i)
          }
          join.out ~> unzipper.in
          // back arc
          loopGate.preferred <~ unzipper.out1

          // we returns single outlet to wrap graph as a Flow
          (base.inlet, unzipper.out0)
    }
      .named("flowZipWithLoop")

  }

}