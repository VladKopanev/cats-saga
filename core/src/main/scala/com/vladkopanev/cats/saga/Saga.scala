package com.vladkopanev.cats.saga

import cats.{Parallel, _}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, Fiber}
import cats.implicits._
import com.vladkopanev.cats.saga.Saga.{
  CompensateFailed,
  CompensateSucceeded,
  Failed,
  FlatMap,
  Noop,
  Par,
  SagaErr,
  Step,
  Suceeded
}
import retry._

/**
 * A Saga is an immutable structure that models a distributed transaction.
 *
 * @see [[https://blog.couchbase.com/saga-pattern-implement-business-transactions-using-microservices-part/ Saga pattern]]
 *
 *      Saga class is effect polymorphic in `F`. `A` parameter is a type of saga result.
 *      Saga collects effects and their compensating actions into a tree which is then transforms to `F` on `transact` method call.
 *      If error occurs Saga will execute compensating actions starting from action that corresponds to failed request
 *      till the first already completed request.
 * */
sealed abstract class Saga[F[_], A] {

  /**
   * Maps the resulting value `A` of this Saga to value `B` with function `f`.
   * */
  def map[B](f: A => B): Saga[F, B] =
    flatMap(a => Saga.Suceeded(f(a)))

  /**
   * Sequences the result of this Saga to the next Saga.
   * */
  def flatMap[B](f: A => Saga[F, B]): Saga[F, B] =
    Saga.FlatMap(this, (a: A) => f(a))

  /**
   * Flattens the structure of this Saga by executing outer Saga first and then executes inner Saga.
   * */
  def flatten[B](implicit ev: A <:< Saga[F, B]): Saga[F, B] =
    flatMap(ev)

  /**
   * Materializes this Saga to effect `F` using MonadError typeclass instance.
   * */
  def transact(implicit F: MonadError[F, Throwable]): F[A] = {
    def interpret[X](saga: Saga[F, X]): F[(X, F[Unit])] = saga match {
      case Suceeded(value) => F.pure((value, F.unit))
      case Failed(err) => F.raiseError(SagaErr(err, F.unit))
      case Noop(computation) => computation.attempt.flatMap {
        case Right(x)     => F.pure((x, F.unit))
        case Left(ex) => F.raiseError(SagaErr(ex, F.unit))
      }
      case s: Step[F, X, Throwable] =>
        s.action.attempt.flatMap {
          case r @ Right(x)     => F.pure((x, s.compensate(r)))
          case e @ Left(ex) => F.raiseError(SagaErr(ex, s.compensate(e)))
        }
      case s: CompensateFailed[F, X, Throwable] =>
        s.action.attempt.flatMap {
          case Right(x) => F.pure((x, F.unit))
          case Left(ex) => F.raiseError(SagaErr(ex, s.compensate(ex)))
        }
      case s: CompensateSucceeded[F, X] =>
        s.action.attempt.flatMap {
          case Right(x) => F.pure((x, s.compensate(x)))
          case Left(ex) => F.raiseError(SagaErr(ex, F.unit))
        }
      case FlatMap(chained: Saga[F, Any], continuation: (Any => Saga[F, X])) =>
        interpret(chained).flatMap {
          case (v, prevStepCompensator) =>
            interpret(continuation(v)).attempt.flatMap {
              case Right((x, currCompensator)) => F.pure((x, currCompensator *> prevStepCompensator))
              case Left(ex: SagaErr[F])        => F.raiseError(ex.copy(compensator = ex.compensator *> prevStepCompensator))
              case Left(err)                   =>
                //should not be here
                F.raiseError(err)
            }
        }
      case Par(left: Saga[F, Any], right: Saga[F, Any], combine: ((Any, Any) => X), compensate, concurrentInstance) =>
        def coordinate[A, B, C](f: (A, B) => C)(
          fasterSaga: Either[Throwable, (A, F[Unit])],
          slowerSaga: Fiber[F, (B, F[Unit])]
        ): F[(C, F[Unit])] = fasterSaga match {
          case Right((a, compA)) =>
            slowerSaga.join.attempt.flatMap[(C, F[Unit])] {
              case Right((b, compB))   => F.pure(f(a, b) -> compensate(compB, compA))
              case Left(e: SagaErr[F]) => F.raiseError(e.copy(compensator = compensate(e.compensator, compA)))
              case Left(err)           =>
                //should not be here
                F.raiseError(err)
            }
          case Left(e: SagaErr[F]) =>
            slowerSaga.join.attempt.flatMap[(C, F[Unit])] {
              case Right((b, compB)) => F.raiseError(e.copy(compensator = compensate(compB, e.compensator)))
              case Left(ea: SagaErr[F]) =>
                ea.cause.addSuppressed(e.cause)
                F.raiseError(ea.copy(compensator = compensate(ea.compensator, e.compensator)))
              case Left(err) =>
                //should not be here
                F.raiseError(err)
            }
          case Left(err) =>
            //should not be here
            F.raiseError(err)
        }

        val fliped = (b: Any, a: Any) => combine(a, b)

        race(interpret(left), interpret(right))(coordinate(combine), coordinate(fliped))(concurrentInstance)
    }

    interpret(this).map(_._1).handleErrorWith {
      case e: SagaErr[F] => e.compensator.orElse(F.unit) *> F.raiseError(e.cause)
    }
  }

  /**
   * Returns Saga that will execute this Saga in parallel with other, combining the result in a tuple.
   * Both compensating actions would be executed in case of failure.
   * */
  def zipPar[B](that: Saga[F, B])(implicit C: Concurrent[F]): Saga[F, (A, B)] =
    zipWithPar(that)((_, _))

  /**
   * Returns Saga that will execute this Saga in parallel with other, combining the result with specified function `f`.
   * Both compensating actions would be executed in case of failure.
   * */
  def zipWithPar[B, C](that: Saga[F, B])(f: (A, B) => C)(implicit C: Concurrent[F]): Saga[F, C] =
    zipWithParAll(that)(f)(_ *> _)

  /**
   * Returns Saga that will execute this Saga in parallel with other, combining the result with specified function `f`
   * and combining the compensating actions with function `g` (this allows user to choose a strategy of running both
   * compensating actions e.g. in sequence or in parallel).
   * */
  def zipWithParAll[B, C](that: Saga[F, B])
                         (f: (A, B) => C)
                         (g: (F[Unit], F[Unit]) => F[Unit])
                         (implicit C: Concurrent[F]): Saga[F, C] =
    Saga.Par(this, that, f, g, C)

  /**
   * Degraded `raceWith` function implementation from `ZIO`
   * */
  private def race[A, B, C](fA: F[A], fB: F[B])(
    leftDone: (Either[Throwable, A], Fiber[F, B]) => F[C],
    rightDone: (Either[Throwable, B], Fiber[F, A]) => F[C]
  )(implicit F: Concurrent[F]) = {
    def arbiter[A1, B1](
      f: (Either[Throwable, A1], Fiber[F, B1]) => F[C],
      loser: Fiber[F, B1],
      race: Ref[F, Int],
      done: Deferred[F, Either[Throwable, C]]
    )(res: Either[Throwable, A1]): F[Unit] =
      race.modify(c => (c + 1) -> (if (c > 0) F.unit else f(res, loser).attempt >>= done.complete)).flatten

    import cats.effect.syntax.bracket._
    import cats.effect.syntax.concurrent._
    for {
      done <- Deferred[F, Either[Throwable, C]]
      race <- Ref.of[F, Int](0)
      child <- Ref.of[F, F[Unit]](F.unit)
      c <- ((for {
            left  <- fA.start.flatTap(f => child.update(_ *> f.cancel))
            right <- fB.start.flatTap(f => child.update(_ *> f.cancel))
            _     <- left.join.attempt.flatMap(arbiter(leftDone, right, race, done)).start
            _     <- right.join.attempt.flatMap(arbiter(rightDone, left, race, done)).start
          } yield ()).uncancelable *> done.get.flatMap(_.fold[F[C]](F.raiseError, F.pure))).onCancel(child.get.flatten)
    } yield c
  }
}

object Saga {

  private case class Suceeded[F[_], A](value: A) extends Saga[F, A]
  private case class Failed[F[_], A](value: Throwable) extends Saga[F, A]
  private case class Noop[F[_], A](action: F[A]) extends Saga[F, A]
  private case class Step[F[_], A, E <: Throwable](action: F[A], compensate: Either[E, A] => F[Unit])
      extends Saga[F, A]
  private case class CompensateFailed[F[_], A, E <: Throwable](action: F[A], compensate: E => F[Unit])
    extends Saga[F, A]
  private case class CompensateSucceeded[F[_], A](action: F[A], compensate: A => F[Unit]) extends Saga[F, A]
  private case class FlatMap[F[_], A, B](fa: Saga[F, A], f: A => Saga[F, B]) extends Saga[F, B]
  private case class Par[F[_], A, B, C](
    fa: Saga[F, A],
    fb: Saga[F, B],
    combine: (A, B) => C,
    compensate: (F[Unit], F[Unit]) => F[Unit],
    concurrent: Concurrent[F]
  ) extends Saga[F, C]

  private case class SagaErr[F[_]](cause: Throwable, compensator: F[Unit]) extends Throwable(cause)

  /**
   * Constructs new Saga from action and compensating action.
   * */
  def compensate[F[_], A](comp: F[A], compensation: F[Unit]): Saga[F, A] =
    compensate(comp, (_: Either[_, _]) => compensation)

  /**
   * Constructs new Saga from action and compensation function that will be applied the result of this request.
   * */
  def compensate[F[_], E <: Throwable, A](comp: F[A], compensation: Either[E, A] => F[Unit]): Saga[F, A] =
    Step(comp, compensation)

  /**
   * Constructs new Saga from action and compensation function that will be applied only to failed result of this request.
   * If given action succeeds associated compensating action would not be executed during the compensation phase.
   * */
  def compensateIfFail[F[_], E <: Throwable, A](request: F[A], compensation: E => F[Unit]): Saga[F, A] =
    CompensateFailed(request, compensation)

  /**
   * Constructs new Saga from action and compensation function that will be applied only to successful result of this request.
   * If given action fails associated compensating action would not be executed during the compensation phase.
   * */
  def compensateIfSuccess[F[_], A](request: F[A], compensation: A => F[Unit]): Saga[F, A] =
    CompensateSucceeded(request, compensation)

  /**
   * Runs all Sagas in iterable in parallel and collects
   * the results.
   */
  def collectAllPar[F[_]: Concurrent, A](sagas: Iterable[Saga[F, A]]): Saga[F, List[A]] =
    foreachPar[F, Saga[F, A], A](sagas)(identity)

  /**
   * Runs all Sagas in iterable in parallel, and collect
   * the results.
   */
  def collectAllPar[F[_]: Concurrent, A](saga: Saga[F, A], rest: Saga[F, A]*): Saga[F, List[A]] =
    collectAllPar(saga +: rest)

  /**
   * Constructs Saga without compensation that fails with an error.
    **/
  def fail[F[_], A](error: Throwable): Saga[F, A] =
    Failed(error)

  /**
   * Constructs a Saga that applies the function `f` to each element of the `Iterable[A]` in parallel,
   * and returns the results in a new `List[B]`.
   *
   */
  def foreachPar[F[_], A, B](as: Iterable[A])(fn: A => Saga[F, B])(implicit C: Concurrent[F]): Saga[F, List[B]] =
    as.foldRight[Saga[F, List[B]]](Saga.noCompensate(C.pure(Nil))) { (a, io) =>
      fn(a).zipWithPar(io)((b, bs) => b :: bs)
    }

  /**
   * Constructs new `no-op` Saga that will do nothing on error.
   * */
  def noCompensate[F[_], A](comp: F[A]): Saga[F, A] =
    Noop(comp)

  /**
   * Constructs new Saga from action, compensating action and a scheduling policy for retrying compensation.
   * */
  def retryableCompensate[F[_], A](request: F[A], compensator: F[Unit], policy: RetryPolicy[F])(
    implicit F: MonadError[F, Throwable],
    S: Sleep[F]
  ): Saga[F, A] = {
    val retry =
      retryingOnAllErrors[Unit][F, Throwable](policy, (_: Throwable, _: RetryDetails) => F.unit)(compensator)
    compensate(request, retry)
  }

  /**
   * Constructs Saga without compensation that succeeds with a strict value.
   * */
  def succeed[F[_], A](value: A): Saga[F, A] =
    Suceeded(value)

  implicit class Compensable[F[_], A](val request: F[A]) {

    def compensate(compensator: F[Unit]): Saga[F, A] = Saga.compensate(request, compensator)

    def compensate[E <: Throwable](compensation: Either[E, A] => F[Unit]): Saga[F, A] =
      Saga.compensate(request, compensation)

    def compensateIfFail[E <: Throwable](compensation: E => F[Unit])(implicit F: InvariantMonoidal[F]): Saga[F, A] =
      Saga.compensateIfFail(request, compensation)

    def compensateIfSuccess(compensation: A => F[Unit])(implicit F: InvariantMonoidal[F]): Saga[F, A] =
      Saga.compensateIfSuccess(request, compensation)

    def noCompensate(implicit F: InvariantMonoidal[F]): Saga[F, A] = Saga.noCompensate(request)

    def retryableCompensate(
      compensator: F[Unit],
      policy: RetryPolicy[F]
    )(implicit F: MonadError[F, Throwable], S: Sleep[F]): Saga[F, A] =
      Saga.retryableCompensate(request, compensator, policy)

  }

  implicit def monad[F[_]]: Monad[Saga[F, *]] = new Monad[Saga[F, *]] {
    override def pure[A](x: A): Saga[F, A] = Saga.succeed(x)

    override def flatMap[A, B](fa: Saga[F, A])(f: A => Saga[F, B]): Saga[F, B] = fa.flatMap(f)

    override def tailRecM[A, B](a: A)(f: A => Saga[F, Either[A, B]]): Saga[F, B] = flatMap(f(a)) {
      case Left(aa) => tailRecM(aa)(f)
      case Right(b) => pure(b)
    }
  }

  type ParF[F[_], +A] = ParF.Type[F, A]

  object ParF {
    type Base
    trait Tag extends Any
    type Type[F[_], +A] <: Base with Tag

    def apply[F[_], A](fa: Saga[F, A]): Type[F, A] =
      fa.asInstanceOf[Type[F, A]]

    def unwrap[F[_], A](fa: Type[F, A]): Saga[F, A] =
      fa.asInstanceOf[Saga[F, A]]
  }

  implicit def applicative[M[_]: Concurrent]: Applicative[ParF[M, *]] = new Applicative[ParF[M, *]] {
    import ParF.{unwrap, apply => par}

    override def pure[A](x: A): ParF[M, A] = par(Saga.succeed(x))

    override def ap[A, B](ff: ParF[M, A => B])(fa: ParF[M, A]): ParF[M, B] =
      par(unwrap(ff).zipWithPar(unwrap(fa)) { (fab, b) => fab(b) })
  }

  implicit def parallel[M[_]: Concurrent]: Parallel.Aux[Saga[M, *], ParF[M, *]] = new Parallel[Saga[M, *]] {

    override type F[x] = ParF[M, x]

    final override val applicative: Applicative[ParF[M, *]] = Saga.applicative[M]

    final override val monad: Monad[Saga[M, *]] = Saga.monad[M]

    override val sequential: F ~> Saga[M, *] = λ[F ~> Saga[M, *]](ParF.unwrap(_))

    override val parallel: Saga[M, *] ~> F = λ[Saga[M, *] ~> F](ParF(_))
  }
}
