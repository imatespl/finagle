package com.twitter.finagle.service

import com.twitter.conversions.time._
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.{
  CancelledRequestException, ChannelClosedException, Service,
  SimpleFilter, TimeoutException, WriteException
}
import com.twitter.util.{Function => _, _}
import java.util.{concurrent => juc}
import java.{util => ju}
import scala.collection.JavaConversions._

/**
 * A function defining retry behavior for a given value type `A`.
 */
abstract class RetryPolicy[-A] extends (A => Option[(Duration, RetryPolicy[A])])

/**
 * A retry policy abstract class. This is convenient to use for Java programmers. Simply implement
 * the two abstract methods `shouldRetry` and `backoffAt` and you're good to go!
 */
abstract class SimpleRetryPolicy[A](i: Int) extends RetryPolicy[A]
  with (A => Option[(Duration, RetryPolicy[A])])
{
  def this() = this(0)

  final def apply(e: A) = {
    if (shouldRetry(e)) {
      backoffAt(i) match {
        case Duration.Top =>
          None
        case howlong =>
          Some((howlong, new SimpleRetryPolicy[A](i + 1) {
            def shouldRetry(a: A) = SimpleRetryPolicy.this.shouldRetry(a)
            def backoffAt(retry: Int) = SimpleRetryPolicy.this.backoffAt(retry)
          }))
      }
    } else {
      None
    }
  }

  override def andThen[B](that: Function1[Option[(Duration, RetryPolicy[A])], B]): A => B = that.compose(this)

  override def compose[B](that: Function1[B, A]): B => Option[(Duration, RetryPolicy[A])] = that.andThen(this)

  /**
   * Given a value, decide whether it is retryable. Typically the value is an exception.
   */
  def shouldRetry(a: A): Boolean

  /**
   * Given a number of retries, return how long to wait till the next retry. Note that this is
   * zero-indexed. To implement a finite number of retries, implement a method like:
   *     `if (i > 3) return never`
   */
  def backoffAt(retry: Int): Duration

  /**
   * A convenience method to access Duration.forever from Java. This is a sentinel value that
   * signals no-further-retries.
   */
  final val never = Duration.Top
}

object RetryPolicy extends JavaSingleton {
  object RetryableWriteException {
    def unapply(thr: Throwable): Option[Throwable] = thr match {
      case WriteException(_: CancelledRequestException) => None
      case WriteException(exc) => Some(exc)
      case _ => None
    }
  }

  val WriteExceptionsOnly: PartialFunction[Try[Nothing], Boolean] = {
    case Throw(RetryableWriteException(_)) => true
  }

  val TimeoutAndWriteExceptionsOnly: PartialFunction[Try[Nothing], Boolean] = WriteExceptionsOnly orElse {
    case Throw(_: TimeoutException) => true
  }

  val ChannelClosedExceptionsOnly: PartialFunction[Try[Nothing], Boolean] = {
    case Throw(_: ChannelClosedException) => true
  }

  /**
   * Retry a specific number of times. A `PartialFunction` argument determines
   * which request types are retryable.
   */
  def tries[A](
    numTries: Int,
    shouldRetry: PartialFunction[A, Boolean]
  ): RetryPolicy[A] = {
    backoff[A](Backoff.const(0.second) take (numTries - 1))(shouldRetry)
  }

  /**
   * Retry a specific number of times on WriteExceptions.
   */
  def tries(numTries: Int): RetryPolicy[Try[Nothing]] = tries(numTries, WriteExceptionsOnly)

  /**
   * Retry based on a series of backoffs defined by a `Stream[Duration]`. The
   * stream is consulted to determine the duration after which a request is to
   * be retried. A `PartialFunction` argument determines which request types
   * are retryable.
   */
  def backoff[A](
    backoffs: Stream[Duration]
  )(shouldRetry: PartialFunction[A, Boolean]): RetryPolicy[A] = {
    new RetryPolicy[A] {
      def apply(e: A) = {
        if (shouldRetry.isDefinedAt(e) && shouldRetry(e)) {
          backoffs match {
            case howlong #:: rest =>
              Some((howlong, backoff(rest)(shouldRetry)))
            case _ =>
              None
          }
        } else {
          None
        }
      }
    }
  }

  /**
   * A constructor usable from Java (`backoffs` from `Backoff.toJava`).
   */
  def backoffJava[A](
    backoffs: juc.Callable[ju.Iterator[Duration]],
    shouldRetry: PartialFunction[A, Boolean]
  ): RetryPolicy[A] = {
    backoff[A](backoffs.call().toStream)(shouldRetry)
  }
}

object RetryingService {
  /**
   * Returns a filter that will retry numTries times, but only if encountering a
   * WriteException.
   */
  def tries[Req, Rep](numTries: Int, stats: StatsReceiver): SimpleFilter[Req, Rep] = {
    val fakeTimer = new Timer {
      def schedule(when: Time)(f: => Unit): TimerTask = throw new Exception("illegal use!")
      def schedule(when: Time, period: Duration)(f: => Unit): TimerTask = throw new Exception("illegal use!")
      def stop() { throw new Exception("illegal use!") }
    }

    val policy = RetryPolicy.tries(numTries)
    new RetryingFilter[Req, Rep](policy, fakeTimer, stats)
  }
}

object RetryingFilter {
  def apply[Req, Rep](
    backoffs: Stream[Duration],
    statsReceiver: StatsReceiver = NullStatsReceiver
  )(shouldRetry: PartialFunction[Try[Nothing], Boolean])(implicit timer: Timer) =
    new RetryingFilter[Req, Rep](RetryPolicy.backoff(backoffs)(shouldRetry), timer, statsReceiver)
}

/**
 * A [[com.twitter.finagle.Filter]] that coordinatess retries of subsequent
 * [[com.twitter.finagle.Service Services]]. Requests can be classified as retryable via
 * the argument [[com.twitter.finagle.service.RetryPolicy]].
 */
class RetryingFilter[Req, Rep](
  retryPolicy: RetryPolicy[Try[Nothing]],
  timer: Timer,
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends SimpleFilter[Req, Rep] {
  private[this] val retriesStat = statsReceiver.stat("retries")


  @inline
  private[this] def schedule(d: Duration)(f: => Future[Rep]) = {
    if (d > 0.seconds) {
      val promise = new Promise[Rep]
      timer.schedule(Time.now + d) {
        promise.become(f)
      }
      promise
    } else f
  }

  private[this] def dispatch(
    req: Req,
    service: Service[Req, Rep],
    policy: RetryPolicy[Try[Nothing]],
    count: Int = 0
  ): Future[Rep] = {
    service(req) onSuccess { _ =>
      retriesStat.add(count)
    } rescue { case e =>
      policy(Throw(e)) match {
        case Some((howlong, nextPolicy)) =>
          schedule(howlong) {
            Trace.record("finagle.retry")
            dispatch(req, service, nextPolicy, count + 1)
          }
        case None =>
          retriesStat.add(count)
          Future.exception(e)
      }
    }
  }

  def apply(request: Req, service: Service[Req, Rep]) = dispatch(request, service, retryPolicy)
}

/**
 * Implements various backoff strategies. Strategies are defined by a
 * `Stream[Duration]` and intended for use with
 * [[com.twitter.service.RetryingFilter#backoff]] to determine the duration
 * after which a request is to be retried
 */
object Backoff {
  private[this] def durations(next: Duration, f: Duration => Duration): Stream[Duration] =
    next #:: durations(f(next), f)

  def apply(next: Duration)(f: Duration => Duration) = durations(next, f)

  def exponential(start: Duration, multiplier: Int) =
    Backoff(start) { _ * multiplier }

  def linear(start: Duration, offset: Duration) =
    Backoff(start) { _ + offset }

  /* Alias because `const' is a reserved word in Java */
  def constant(start: Duration) = const(start)

  def const(start: Duration) =
    Backoff(start)(Function.const(start))

  /**
   * Convert a {{Stream[Duration]}} into a Java-friendly representation.
   */
  def toJava(backoffs: Stream[Duration]): ju.concurrent.Callable[ju.Iterator[Duration]] = {
    new ju.concurrent.Callable[ju.Iterator[Duration]] {
      def call() = backoffs.toIterator
    }
  }
}
