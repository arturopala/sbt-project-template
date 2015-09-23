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

  def zip[A, B, Mat](left: Source[A, Mat], right: Source[B, _]): Source[(A, B), Mat] = Source(left) {
    implicit b =>
      source =>
        val zipper = b.add(Zip[A, B])
        source ~> zipper.in0
        right ~> zipper.in1
        zipper.out
  }

  def merge[A, B, C, Mat](left: Source[A, Mat], right: Source[B, _], f: (A, B) => C): Source[C, Mat] = Source(left) {
    implicit b =>
      source =>
        val zipper = b.add(ZipWith[A, B, C](f))
        source ~> zipper.in0
        right ~> zipper.in1
        zipper.out
  }

  def throttle[A, B, Mat](source: Source[A, Mat], clock: Source[B, _]): Source[A, Mat] = merge(source, clock, (a: A, b: B) => a)
  def throttle[A, Mat](period: FiniteDuration, source: Source[A, Mat]): Source[A, Mat] = throttle(source, Clock(period))

  implicit class Ops[A, B, Mat](source: Source[A, Mat]) {
    def zip(other: Source[B, _]): Source[(A, B), Mat] = MoreFlowOps.zip(source, other)
    def merge[C](other: Source[B, _], f: (A, B) => C): Source[C, Mat] = MoreFlowOps.merge(source, other, f)
    def throttle(period: FiniteDuration): Source[A, Mat] = MoreFlowOps.throttle(period, source)
    def throttle[B](clock: Source[B, _]): Source[A, Mat] = MoreFlowOps.throttle(source, clock)
  }

  object Clock {
    trait Tick
    case object Tick extends Tick
    def apply(period: FiniteDuration): Source[Tick, Cancellable] = Source(0.millis, period, Tick)
  }

}