package com.vladkopanev.cats.saga

import cats.effect.kernel.Spawn
import cats.implicits._
import cats.{Parallel, _}
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
  def transact(implicit SI: SagaTransactor[F], F: MonadError[F, Throwable]): F[A] =
    SI.transact(this)

  /**
   * Returns Saga that will execute this Saga in parallel with other, combining the result in a tuple.
   * Both compensating actions would be executed in case of failure.
   * */
  def zipPar[B](that: Saga[F, B])(implicit S: Spawn[F]): Saga[F, (A, B)] =
    zipWithPar(that)((_, _))

  /**
   * Returns Saga that will execute this Saga in parallel with other, combining the result with specified function `f`.
   * Both compensating actions would be executed in case of failure.
   * */
  def zipWithPar[B, C](that: Saga[F, B])(f: (A, B) => C)(implicit S: Spawn[F]): Saga[F, C] =
    zipWithParAll(that)(f)(_ *> _)

  /**
   * Returns Saga that will execute this Saga in parallel with other, combining the result with specified function `f`
   * and combining the compensating actions with function `g` (this allows user to choose a strategy of running both
   * compensating actions e.g. in sequence or in parallel).
   * */
  def zipWithParAll[B, C](
    that: Saga[F, B]
  )(f: (A, B) => C)(g: (F[Unit], F[Unit]) => F[Unit])(implicit S: Spawn[F]): Saga[F, C] =
    Saga.Par(this, that, f, g, S)
}

object Saga {

  private[saga] case class Suceeded[F[_], A](value: A)                                                      extends Saga[F, A]
  private[saga] case class Failed[F[_], A](value: Throwable)                                                extends Saga[F, A]
  private[saga] case class Noop[F[_], A](action: F[A])                                                      extends Saga[F, A]
  private[saga] case class Step[F[_], A, E <: Throwable](action: F[A], compensate: Either[E, A] => F[Unit]) extends Saga[F, A]
  private[saga] case class CompensateFailed[F[_], A, E <: Throwable](action: F[A], compensate: E => F[Unit])
      extends Saga[F, A]
  private[saga] case class CompensateSucceeded[F[_], A](action: F[A], compensate: A => F[Unit]) extends Saga[F, A]
  private[saga] case class FlatMap[F[_], A, B](fa: Saga[F, A], f: A => Saga[F, B])              extends Saga[F, B]
  private[saga] case class Par[F[_], A, B, C](
    fa: Saga[F, A],
    fb: Saga[F, B],
    combine: (A, B) => C,
    compensate: (F[Unit], F[Unit]) => F[Unit],
    concurrent: Spawn[F]
  ) extends Saga[F, C]

  private[saga] case class SagaErr[F[_]](cause: Throwable, compensator: F[Unit]) extends Throwable(cause)

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
  def collectAllPar[F[_]: Spawn, A](sagas: Iterable[Saga[F, A]]): Saga[F, List[A]] =
    foreachPar[F, Saga[F, A], A](sagas)(identity)

  /**
   * Runs all Sagas in iterable in parallel, and collect
   * the results.
   */
  def collectAllPar[F[_]: Spawn, A](saga: Saga[F, A], rest: Saga[F, A]*): Saga[F, List[A]] =
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
  def foreachPar[F[_], A, B](as: Iterable[A])(fn: A => Saga[F, B])(implicit S: Spawn[F]): Saga[F, List[B]] =
    as.foldRight[Saga[F, List[B]]](Saga.noCompensate(S.pure(Nil))) { (a, io) =>
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

    def compensateIfFail[E <: Throwable](compensation: E => F[Unit]): Saga[F, A] =
      Saga.compensateIfFail(request, compensation)

    def compensateIfSuccess(compensation: A => F[Unit]): Saga[F, A] =
      Saga.compensateIfSuccess(request, compensation)

    def noCompensate: Saga[F, A] = Saga.noCompensate(request)

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

  implicit def applicative[M[_]: Spawn]: Applicative[ParF[M, *]] = new Applicative[ParF[M, *]] {
    import ParF.{unwrap, apply => par}

    override def pure[A](x: A): ParF[M, A] = par(Saga.succeed(x))

    override def ap[A, B](ff: ParF[M, A => B])(fa: ParF[M, A]): ParF[M, B] =
      par(unwrap(ff).zipWithPar(unwrap(fa)) { (fab, b) =>
        fab(b)
      })
  }

  implicit def parallel[M[_]: Spawn]: Parallel.Aux[Saga[M, *], ParF[M, *]] = new Parallel[Saga[M, *]] {

    override type F[x] = ParF[M, x]

    final override val applicative: Applicative[ParF[M, *]] = Saga.applicative[M]

    final override val monad: Monad[Saga[M, *]] = Saga.monad[M]

    override val sequential: F ~> Saga[M, *] = λ[F ~> Saga[M, *]](ParF.unwrap(_))

    override val parallel: Saga[M, *] ~> F = λ[Saga[M, *] ~> F](ParF(_))
  }
}
