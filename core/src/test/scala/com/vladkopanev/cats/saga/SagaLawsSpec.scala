package com.vladkopanev.cats.saga

import cats.Eq
import cats.effect.IO
import cats.effect.testkit.TestInstances
import cats.implicits._
import cats.laws.discipline.{ ApplicativeTests, MonadTests, ParallelTests }
import com.vladkopanev.cats.saga.Saga.ParF
import org.scalacheck.{ Arbitrary, Cogen, Gen }
import org.scalatest.funspec.AnyFunSpecLike
import org.scalatest.prop.Configuration
import org.typelevel.discipline.scalatest.FunSpecDiscipline

class SagaLawsSpec extends FunSpecDiscipline with AnyFunSpecLike with Configuration {
  import arbitraries._
  checkAll("Saga.MonadLaws", MonadTests[Saga[IO, *]].monad[Int, Int, String])
  checkAll("Saga.ApplicativeLaws", ApplicativeTests[Saga[IO, *]].applicative[Int, Int, String])
  checkAll("Saga.ParallelLaws", ParallelTests[Saga[IO, *]].parallel[Int, String])
}

object arbitraries extends TestInstances {

  implicit val ticker = Ticker()

  implicit def lawsArbitraryForSaga[A: Arbitrary: Cogen]: Arbitrary[Saga[IO, A]] =
    Arbitrary(Gen.delay(genSaga[A]))

  implicit def lawsArbitraryForSagaParallel[A: Arbitrary: Cogen]: Arbitrary[Saga.ParF[IO, A]] =
    Arbitrary(lawsArbitraryForSaga[A].arbitrary.map(ParF.apply))

  def genSaga[A: Arbitrary: Cogen]: Gen[Saga[IO, A]] =
    Gen.frequency(
      1 -> genSucceed[A],
      1 -> genNoCompensate[A],
      1 -> genFlatMap[A],
      1 -> genFail[A],
      1 -> genMapOne[A],
      1 -> genMapTwo[A]
    )

  def genSucceed[A: Arbitrary]: Gen[Saga[IO, A]] =
    Arbitrary.arbitrary[A].map(Saga.succeed)

  def genNoCompensate[A: Arbitrary: Cogen]: Gen[Saga[IO, A]] =
    Arbitrary.arbitrary[IO[A]].map(Saga.noCompensate)

  def genFail[A: Arbitrary]: Gen[Saga[IO, A]] =
    Arbitrary.arbitrary[Throwable].map(Saga.fail)

  def genFlatMap[A: Arbitrary: Cogen]: Gen[Saga[IO, A]] =
    for {
      ioa <- Arbitrary.arbitrary[Saga[IO, A]]
      f   <- Arbitrary.arbitrary[A => Saga[IO, A]]
    } yield ioa.flatMap(f)

  def genMapOne[A: Arbitrary: Cogen]: Gen[Saga[IO, A]] =
    for {
      ioa <- Arbitrary.arbitrary[Saga[IO, A]]
      f   <- Arbitrary.arbitrary[A => A]
    } yield ioa.map(f)

  def genMapTwo[A: Arbitrary: Cogen]: Gen[Saga[IO, A]] =
    for {
      ioa <- Arbitrary.arbitrary[Saga[IO, A]]
      f   <- Arbitrary.arbitrary[A => A]
      g   <- Arbitrary.arbitrary[A => A]
    } yield ioa.map(f).map(g)

  import Saga._
  def genCompensate[A: Arbitrary: Cogen]: Gen[Saga[IO, A]] =
    for {
      action       <- Arbitrary.arbitrary[IO[A]]
      compensation <- Arbitrary.arbitrary[IO[Unit]]
    } yield action.compensate(compensation)

  implicit val si: SagaTransactor[IO] = new SagaDefaultTransactor[IO]
  implicit def eqSaga[A: Eq]: Eq[Saga[IO, A]] =
    Eq.by(_.transact)

  implicit def eqParSaga[A: Eq]: Eq[Saga.ParF[IO, A]] =
    Eq.instance { case (x, y) => eqSaga[A].eqv(ParF.unwrap(x), ParF.unwrap(y)) }
}
