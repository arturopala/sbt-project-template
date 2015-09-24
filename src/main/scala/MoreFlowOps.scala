import scala.concurrent.{ Future, Promise, Await }
import scala.util.{ Try, Success, Failure }
import scala.util.control.NonFatal
import scala.concurrent.duration._
import akka.actor.{ ActorSystem, Cancellable }
import akka.event.Logging
import akka.stream._
import akka.stream.scaladsl._
import FlowGraph.Implicits._

object MoreFlowOps {

  def merge[A, B, C, Mat](left: Source[A, Mat], right: Source[B, _], f: (A, B) => C): Source[C, Mat] = Source(left) {
    implicit b =>
      source =>
        val zipper = b.add(ZipWith[A, B, C](f))
        source ~> zipper.in0
        right ~> zipper.in1
        zipper.out
  }
    .named("merge")

  def zip[A, B, Mat](left: Source[A, Mat], right: Source[B, _]): Source[(A, B), Mat] = merge(left, right, (a: A, b: B) => (a, b)).named("zip")
  def throttle[A, B, Mat](source: Source[A, Mat], clock: Source[B, _]): Source[A, Mat] = merge(source, clock, (a: A, b: B) => a).named("throttle")
  def throttle[A, Mat](period: FiniteDuration, source: Source[A, Mat]): Source[A, Mat] = throttle(source, Clock(period)).named("clock")

  implicit class Ops[A, Mat](source: Source[A, Mat]) {
    def zip[B](other: Source[B, _]): Source[(A, B), Mat] = MoreFlowOps.zip(source, other)
    def merge[B, C](other: Source[B, _], f: (A, B) => C): Source[C, Mat] = MoreFlowOps.merge(source, other, f)
    def throttle(period: FiniteDuration): Source[A, Mat] = MoreFlowOps.throttle(period, source)
    def throttle[B](clock: Source[B, _]): Source[A, Mat] = MoreFlowOps.throttle(source, clock)
    def viaLoop[B, C](parallelism: Int)(loopSource: Source[B, _], flow: Flow[(A, B), (C, B), Mat]): Source[C, Mat] = Loop.viaLoop(parallelism)(loopSource, source, flow)
  }

  object Clock {

    trait Tick
    case object Tick extends Tick

    def apply(period: FiniteDuration): Source[Tick, Cancellable] = Source(0.millis, period, Tick)
  }

  object Loop {

    def viaLoop[A, B, C, Mat](parallelism: Int)(loopSource: Source[B, _], source: Source[A, Mat], flow: Flow[(A, B), (C, B), Mat]): Source[C, Mat] = Source(source) {
      implicit b =>
        source =>

          assert(parallelism > 0)
          // junctions setup
          val loopGate = b.add(MergePreferred[B](1))
          val zipper = b.add(Zip[A, B])
          val balancer = b.add(Balance[(A, B)](parallelism))
          val merger = b.add(Merge[(C, B)](parallelism))
          val unzipper = b.add(Unzip[C, B])

          // components wiring
          source ~> zipper.in0
          loopSource ~> loopGate.in(0)
          loopGate.out ~> zipper.in1
          zipper.out ~> balancer.in
          // multiple flow copies balanced
          for (i <- 0 until parallelism) {
            balancer.out(i) ~> flow ~> merger.in(i)
          }
          merger.out ~> unzipper.in
          unzipper.out1 ~> loopGate.preferred

          // we returns single outlet to wrap graph as a Source
          unzipper.out0
    }
      .named("loop")

  }

}