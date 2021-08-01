package com.vladkopanev.cats.saga

import cats.MonadError
import cats.effect.{ Fiber, Spawn }
import cats.effect.kernel.{ MonadCancel, Outcome }
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
import cats.syntax.all._

trait SagaTransactor[F[_]] {
  def transact[A](saga: Saga[F, A])(implicit F: MonadError[F, Throwable]): F[A]
}

class SagaDefaultTransactor[F[_]] extends SagaTransactor[F] {

  def transact[A](saga: Saga[F, A])(implicit F: MonadError[F, Throwable]): F[A] = {
    def run[X](s: Saga[F, X]): F[(X, F[Unit])] = s match {
      case Suceeded(value) => F.pure((value, F.unit))
      case Failed(err)     => F.raiseError(SagaErr(err, F.unit))
      case Noop(computation) =>
        computation.attempt.flatMap {
          case Right(x) => F.pure((x, F.unit))
          case Left(ex) => F.raiseError(SagaErr(ex, F.unit))
        }
      case s: Step[F, X, Throwable] =>
        s.action.attempt.flatMap {
          case r @ Right(x) => F.pure((x, s.compensate(r)))
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
        run(chained).flatMap {
          case (v, prevStepCompensator) =>
            run(continuation(v)).attempt.flatMap {
              case Right((x, currCompensator)) => F.pure((x, currCompensator *> prevStepCompensator))
              case Left(ex: SagaErr[F])        => F.raiseError(ex.copy(compensator = ex.compensator *> prevStepCompensator))
              case Left(err)                   =>
                //should not be here
                F.raiseError(err)
            }
        }
      case Par(
          left: Saga[F, Any],
          right: Saga[F, Any],
          combine: ((Any, Any) => X),
          combineCompensations,
          spawnInstance
          ) =>
        implicit val spawn: Spawn[F] = spawnInstance
        def coordinate[A, B, C](f: (A, B) => C)(
          fasterSaga: Outcome[F, Throwable, (A, F[Unit])],
          slowerSaga: Fiber[F, Throwable, (B, F[Unit])]
        ): F[(C, F[Unit])] = fasterSaga match {
          case Outcome.Succeeded(fa) =>
            fa.flatMap {
              case (a, compA) =>
                slowerSaga.join.flatMap[(C, F[Unit])] {
                  case Outcome.Succeeded(fA) =>
                    fA.map { case (b, compB) => f(a, b) -> combineCompensations(compB, compA) }
                  case Outcome.Errored(e: SagaErr[F]) =>
                    F.raiseError(e.copy(compensator = combineCompensations(e.compensator, compA)))
                  case Outcome.Canceled() =>
                    //should not be here as we wrap our fibers in uncancelable
                    MonadCancel[F].canceled >> Spawn[F].never[(C, F[Unit])]
                  case Outcome.Errored(err) =>
                    //should not be here
                    F.raiseError(err)
                }
            }
          case Outcome.Errored(e: SagaErr[F]) =>
            slowerSaga.join.flatMap[(C, F[Unit])] {
              case Outcome.Succeeded(fA) =>
                fA.flatMap {
                  case (_, compB) => F.raiseError(e.copy(compensator = combineCompensations(compB, e.compensator)))
                }
              case Outcome.Errored(ea: SagaErr[F]) =>
                ea.cause.addSuppressed(e.cause)
                F.raiseError(ea.copy(compensator = combineCompensations(ea.compensator, e.compensator)))
              case Outcome.Canceled() =>
                //should not be here as we wrap our fibers in uncancelable
                MonadCancel[F].canceled >> Spawn[F].never[(C, F[Unit])]
              case Outcome.Errored(err) =>
                //should not be here
                F.raiseError(err)
            }
          case Outcome.Errored(err) =>
            //should not be here
            F.raiseError(err)
          case Outcome.Canceled() =>
            //should not be here as we wrap our fibers in uncancelable
            MonadCancel[F].canceled >> Spawn[F].never[(C, F[Unit])]
        }

        Spawn[F].racePair(run(left), run(right)).flatMap {
          case Left((fastLeft, slowRight))  => coordinate(combine)(fastLeft, slowRight)
          case Right((slowLeft, fastRight)) => coordinate((b: Any, a: Any) => combine(a, b))(fastRight, slowLeft)
        }
    }

    run(saga).map(_._1).handleErrorWith {
      case e: SagaErr[F] => e.compensator.orElse(F.unit) *> F.raiseError(e.cause)
    }
  }
}
