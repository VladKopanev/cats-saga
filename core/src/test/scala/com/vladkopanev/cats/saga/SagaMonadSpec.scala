package com.vladkopanev.cats.saga

import cats.effect.{ ContextShift, IO }
import cats.implicits._
import cats.laws.discipline.MonadTests
import org.scalacheck.{ Arbitrary, Gen }
import arbitraries._
import cats.Eq

import scala.concurrent.ExecutionContext
import org.scalatest.funsuite.AnyFunSuite

class SagaMonadSpec extends AnyFunSuite with Discipline {

  checkAll("Saga.MonadLaws", MonadTests[Saga[IO, *]].monad[Int, Int, String])

}

object arbitraries {

  implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  implicit def arbSaga[A: Arbitrary]: Arbitrary[Saga[IO, A]] = {
    val succeed: Gen[Saga[IO, A]] = for {
      a <- Arbitrary.arbitrary[A]
    } yield Saga.succeed(a)

    Arbitrary(succeed)
  }

  implicit def eqSaga[A]: Eq[Saga[IO, A]] =
    Eq.instance { case (x, y) => x.transact.unsafeRunSync() == y.transact.unsafeRunSync() }
}
