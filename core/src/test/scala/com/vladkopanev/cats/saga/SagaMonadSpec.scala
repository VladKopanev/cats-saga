package com.vladkopanev.cats.saga

import cats.Eq
import cats.effect.{ContextShift, IO}
import cats.implicits._
import cats.laws.discipline.MonadTests
import com.vladkopanev.cats.saga.arbitraries._
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.funspec.AnyFunSpecLike
import org.scalatest.prop.Configuration
import org.typelevel.discipline.scalatest.FunSpecDiscipline

import scala.concurrent.ExecutionContext

class SagaMonadSpec extends FunSpecDiscipline with AnyFunSpecLike with Configuration {

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
