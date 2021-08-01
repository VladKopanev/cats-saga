package com.vladkopanev.cats.saga

import cats.effect.{Fiber, IO, Outcome, Ref}
import cats.syntax.all._
import com.vladkopanev.cats.saga.CatsSagaSpec._
import com.vladkopanev.cats.saga.Saga._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import retry.RetryPolicies
import cats.effect.unsafe.implicits._

import scala.concurrent.duration.FiniteDuration

class CatsSagaSpec extends AnyFlatSpec with Matchers {

  import scala.concurrent.duration._

  "Saga#map" should "change the result value with provided function" in new TestRuntime {
    val saga = Saga.compensate(IO.pure(1), IO.unit).map(_.toString)
    saga.transact.unsafeRunSync() shouldBe "1"
  }

  "Saga#zipPar" should "successfully run two Sagas" in new TestRuntime {
    val saga = bookFlight compensate cancelFlight zipPar (bookHotel compensate cancelHotel)
    saga.transact.unsafeRunSync() shouldBe ((FlightPayment, HotelPayment))
  }

  "Saga#zipWithPar" should "successfully run two Sagas in parallel" in new TestRuntime {
    val saga = (sleep(1000.millis) *> bookFlight compensate cancelFlight)
      .zipWithPar(sleep(1000.millis) *> bookHotel compensate cancelHotel)((_, _) => ())

    val start = System.currentTimeMillis()
    saga.transact.unsafeRunSync()
    val time = System.currentTimeMillis() - start
    assert(time <= 1500, "Time limit for executing two Sagas in parallel exceeded")
  }

  it should "run both compensating actions in case right request fails" in new TestRuntime {
    val bookFlightS = sleep(1000.millis) *> bookFlight
    val failHotel = sleep(100.millis) *> IO.raiseError(HotelBookingError())

    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      _ <- (bookFlightS compensate cancelFlight(actionLog.update(_ :+ "flight canceled"))).zipWithPar(
        failHotel compensate cancelHotel(actionLog.update(_ :+ "hotel canceled")))((_, _) => ()).transact.orElse(IO.unit)
      log <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog shouldBe Vector("flight canceled", "hotel canceled")
  }

  it should "run both compensating actions in case left request fails" in new TestRuntime {
    val bookFlightS = sleep(1000.millis) *> bookFlight
    val failHotel = sleep(100.millis) *> IO.raiseError(HotelBookingError())

    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      _ <- (failHotel compensate cancelHotel(actionLog.update(_ :+ "hotel canceled"))).zipWithPar(
        bookFlightS compensate cancelFlight(actionLog.update(_ :+ "flight canceled")))((_, _) => ()).transact.orElse(IO.unit)
      log <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog shouldBe Vector("flight canceled", "hotel canceled")
  }

  it should "run both compensating actions in case both requests fails" in new TestRuntime {
    val failFlight = sleep(1000.millis) *> IO.raiseError(FlightBookingError())
    val failHotel = sleep(1000.millis) *> IO.raiseError(HotelBookingError())

    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      _ <- (failFlight compensate cancelFlight(actionLog.update(_ :+ "flight canceled"))).zipWithPar(
        failHotel compensate cancelHotel(actionLog.update(_ :+ "hotel canceled")))((_, _) => ()).transact.orElse(IO.unit)
      log <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog should contain theSameElementsAs Vector("flight canceled", "hotel canceled")
  }

  it should "run compensating actions in order that is opposite to which requests finished" in new TestRuntime {
    val failFlight = sleep(1000.millis) *> IO.raiseError(FlightBookingError())
    val failHotel = sleep(100.millis) *> IO.raiseError(HotelBookingError())

    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      _         <- (failFlight compensate cancelFlight(actionLog.update(_ :+ "flight canceled"))).zipWithPar(
        failHotel compensate cancelHotel(actionLog.update(_ :+ "hotel canceled")))((_, _) => ())
        .transact.orElse(IO.unit)
      log <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog shouldBe Vector("flight canceled", "hotel canceled")
  }

  "Saga" should "run all compensating actions in case of error" in new TestRuntime {
    val failFlightBooking: IO[PaymentInfo] = IO.sleep(1000.millis) *> IO.raiseError(FlightBookingError())
    val bookHotelS        = IO.sleep(600.millis) *> bookHotel
    val bookCarS          = IO.sleep(300.millis) *> bookCar
    val bookCarS2         = IO.sleep(100.millis) *> bookCar

    val sagaIO: IO[Vector[String]] = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      _ <- (for {
            _ <- bookHotelS compensate cancelHotel(actionLog.update(_ :+ "hotel canceled"))
            _ <- bookCarS compensate cancelCar(actionLog.update(_ :+ "car canceled"))
            _ <- failFlightBooking compensate cancelFlight(actionLog.update(_ :+ "flight canceled"))
            _ <- bookCarS2 compensate cancelCar(actionLog.update(_ :+ "car2 canceled"))
      } yield ()).transact.handleErrorWith(_ => IO.unit)
      log <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog shouldBe Vector("flight canceled", "car canceled", "hotel canceled")
  }

  "Saga#collectAllPar" should "construct a Saga that runs several requests in parallel" in new TestRuntime {
    def bookFlightS(log: Ref[IO, Vector[String]]): IO[PaymentInfo] =
      sleep(1000.millis) *> bookFlight <* log.update(_ :+ "flight is booked")
    def bookHotelS(log: Ref[IO, Vector[String]]): IO[PaymentInfo] =
      sleep(600.millis) *> bookHotel <* log.update(_ :+ "hotel is booked")
    def bookCarS(log: Ref[IO, Vector[String]]): IO[PaymentInfo] =
      sleep(300.millis) *> bookCar <* log.update(_ :+ "car is booked")
    def bookCarS2(log: Ref[IO, Vector[String]]): IO[PaymentInfo] =
      sleep(100.millis) *> bookCar <* log.update(_ :+ "car2 is booked")

    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      flight    = bookFlightS(actionLog) compensate cancelFlight
      hotel     = bookHotelS(actionLog) compensate cancelHotel
      car       = bookCarS(actionLog) compensate cancelCar
      car2      = bookCarS2(actionLog) compensate cancelCar
      _         <- Saga.collectAllPar(flight, hotel, car, car2).transact
      log       <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog shouldBe Vector("car2 is booked", "car is booked", "hotel is booked", "flight is booked")
  }

  it should "run all compensating actions in case of error" in new TestRuntime {
    val failFlightBooking: IO[PaymentInfo] = sleep(1000.millis) *> IO.raiseError(FlightBookingError())
    val bookHotelS        = sleep(600.millis) *> bookHotel
    val bookCarS          = sleep(300.millis) *> bookCar
    val bookCarS2         = sleep(100.millis) *> bookCar

    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      flight    = failFlightBooking compensate cancelFlight(actionLog.update(_ :+ "flight canceled"))
      hotel     = bookHotelS compensate cancelHotel(actionLog.update(_ :+ "hotel canceled"))
      car       = bookCarS compensate cancelCar(actionLog.update(_ :+ "car canceled"))
      car2      = bookCarS2 compensate cancelCar(actionLog.update(_ :+ "car2 canceled"))
      _         <- Saga.collectAllPar(List(flight, hotel, car, car2)).transact.orElse(IO.pure(List.empty))
      log       <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog shouldBe Vector("flight canceled", "hotel canceled", "car canceled", "car2 canceled")
  }

  "Saga#succeed" should "construct saga that will succeed" in new TestRuntime {
    val failFlightBooking: IO[PaymentInfo] = IO.raiseError(FlightBookingError())
    val stub              = 1

    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      _ <- (for {
        i <- Saga.succeed(stub)
        _ <- failFlightBooking compensate cancelFlight(actionLog.update(_ :+ s"flight canceled $i"))
      } yield ()).transact.orElse(IO.unit)
      log <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog shouldBe Vector(s"flight canceled $stub")
  }

  "Saga#fail" should "construct saga that will fail" in new TestRuntime {
    val failFlightBooking: IO[PaymentInfo] = IO.raiseError(FlightBookingError())

    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      _ <- (for {
        i <- Saga.fail[IO, Int](FlightBookingError())
        _ <- failFlightBooking compensate cancelFlight(actionLog.update(_ :+ s"flight canceled $i"))
      } yield ()).transact.orElse(IO.unit)
      log <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog shouldBe Vector.empty
  }

  "Saga#retryableCompensate" should "construct Saga that repeats compensating action once" in new TestRuntime {
    val failFlight: IO[Unit] = sleep(1000.millis) *> IO.raiseError(FlightBookingError())

    def failCompensator(log: Ref[IO, Vector[String]]): IO[Unit] =
      cancelFlight(log.update(_ :+ "Compensation failed")) *> IO.raiseError(FlightBookingError())

    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      _         <- (failFlight retryableCompensate (failCompensator(actionLog), RetryPolicies.limitRetries(1))).transact
        .orElse(IO.unit)
      log       <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog shouldBe Vector.fill(2)("Compensation failed")
  }

  it should "work with other combinators" in new TestRuntime {
    val saga = for {
      _ <- bookFlight.noCompensate
      _ <- bookHotel retryableCompensate (cancelHotel, RetryPolicies.limitRetries(1))
      _ <- bookCar compensate cancelCar
    } yield ()

    saga.transact.unsafeRunSync()
  }

  "Saga#flatten" should "execute outer effect first and then the inner one producing the result of it" in new TestRuntime {
    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      outer     = bookFlight compensate cancelFlight(actionLog.update(_ :+ "flight canceled"))
      inner     = bookHotel compensate cancelHotel(actionLog.update(_ :+ "hotel canceled"))
      failCar = IO.raiseError[Unit](CarBookingError()) compensate cancelCar(actionLog.update(_ :+ "car canceled"))
      _   <- outer.map(_ => inner).flatten[PaymentInfo].flatMap(_ => failCar).transact.orElse(IO.unit)
      log <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog shouldBe Vector("car canceled", "hotel canceled", "flight canceled")
  }

  "Saga#compensate" should "allow compensation to be dependent on the result of corresponding effect" in new TestRuntime {
    val failCar: IO[PaymentInfo] = IO.raiseError(CarBookingError())
    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      _ <- (for {
        _ <- bookHotel compensate cancelHotel(actionLog.update(_ :+ "hotel canceled"))
        _ <- bookFlight compensate cancelFlight(actionLog.update(_ :+ "flight canceled"))
        _ <- failCar.compensate((_: Either[SagaError, PaymentInfo]) => cancelCar(actionLog.update(_ :+ "car canceled")))
      } yield ()).transact.orElse(IO.unit)
      log <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog shouldBe Vector("car canceled", "flight canceled", "hotel canceled")
  }

  "Saga#compensateIfFail" should "construct saga step that executes it's compensation if it's requests fails" in new TestRuntime {
    val failCar: IO[Unit] = IO.raiseError(CarBookingError())
    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      _ <- (for {
        _ <- bookFlight compensate cancelFlight(actionLog.update(_ :+ "flight canceled"))
        _ <- bookHotel compensate cancelHotel(actionLog.update(_ :+ "hotel canceled"))
        _ <- failCar.compensateIfFail[SagaError](_ => cancelCar(actionLog.update(_ :+ "car canceled")))
      } yield ()).transact.orElse(IO.unit)
      log <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog shouldBe Vector("car canceled", "hotel canceled", "flight canceled")
  }

  it should "construct saga step that do not executes it's compensation if it's request succeeds" in new TestRuntime {
    val failFlightBooking: IO[Unit] = IO.raiseError(FlightBookingError())
    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      _ <- (for {
        _ <- bookCar compensateIfFail[SagaError](_ => cancelCar(actionLog.update(_ :+ "car canceled")))
        _ <- bookHotel compensate cancelHotel(actionLog.update(_ :+ "hotel canceled"))
        _ <- failFlightBooking compensate cancelFlight(actionLog.update(_ :+ "flight canceled"))
      } yield ()).transact.orElse(IO.unit)
      log <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog shouldBe Vector("flight canceled", "hotel canceled")
  }

  "Saga#compensateIfSuccess" should "construct saga step that executes it's compensation if it's requests succeeds" in new TestRuntime {
    val failFlightBooking: IO[Unit] = IO.raiseError(FlightBookingError())
    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      _ <- (for {
        _ <- bookCar compensateIfSuccess(_ => cancelCar(actionLog.update(_ :+ "car canceled")))
        _ <- bookHotel compensate cancelHotel(actionLog.update(_ :+ "hotel canceled"))
        _ <- failFlightBooking compensate cancelFlight(actionLog.update(_ :+ "flight canceled"))
      } yield ()).transact.orElse(IO.unit)
      log <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog shouldBe Vector("flight canceled", "hotel canceled", "car canceled")
  }

  it should "construct saga step that do not executes it's compensation if it's request fails" in new TestRuntime {
    val failCar: IO[PaymentInfo] = IO.raiseError(CarBookingError())
    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      _ <- (for {
        _ <- bookHotel compensate cancelHotel(actionLog.update(_ :+ "hotel canceled"))
        _ <- bookFlight compensate cancelFlight(actionLog.update(_ :+ "flight canceled"))
        _ <- failCar compensateIfSuccess(_ => cancelCar(actionLog.update(_ :+ "car canceled")))
      } yield ()).transact.orElse(IO.unit)
      log <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()
    actionLog shouldBe Vector("flight canceled", "hotel canceled")
  }

  "Saga#zipWithParAll" should "allow combining compensations in parallel" in new TestRuntime {
    val failFlight = IO.raiseError(FlightBookingError())
    val failHotel = IO.raiseError(HotelBookingError())

    def cancelFlightC(actionLog: Ref[IO, Vector[String]]) = sleep(100.millis) *>
      cancelFlight(actionLog.update(_ :+ "flight canceled"))
    def cancelHotelC(actionLog: Ref[IO, Vector[String]]) = sleep(100.millis) *>
      cancelHotel(actionLog.update(_ :+ "hotel canceled"))

    type OutcomeUnit = Outcome[IO, Throwable, Unit]
    private def awaitCompensation(r: Either[(OutcomeUnit, Fiber[IO, Throwable, Unit]), (Fiber[IO, Throwable, Unit], OutcomeUnit)]) =
      r.fold(_._2.join, _._1.join)

    val sagaIO = for {
      actionLog <- Ref.of[IO, Vector[String]](Vector.empty[String])
      _         <- (failFlight compensate cancelFlightC(actionLog)).zipWithParAll(
        failHotel compensate cancelHotelC(actionLog)
      )((_, _) => ())((a, b) => IO.racePair(a, b).flatMap(awaitCompensation).void)
        .transact.orElse(IO.unit)
      log <- actionLog.get
    } yield log

    val actionLog = sagaIO.unsafeRunSync()

    actionLog should contain theSameElementsAs Vector("flight canceled", "hotel canceled")
  }

  "Saga#transact" should "return original error in case compensator also fails" in new TestRuntime {
    val expectedError = FlightBookingError()
    val failFlight: IO[Any] = sleep(1000.millis) *> IO.raiseError(expectedError)

    val failCompensator = cancelFlight *> IO.raiseError(new RuntimeException())

    val saga = (failFlight compensate failCompensator).transact.handleErrorWith(e => IO.pure(e))

    val actualError = saga.unsafeRunSync()
    actualError shouldBe expectedError
  }

  "Saga#transact" should "return original error in case compensator also fails 2" in new TestRuntime {
    val expectedError = FlightBookingError()
    val failFlight: IO[Any] = sleep(1000.millis) *> IO.raiseError(expectedError)

    val failCompensator = cancelFlight *> IO.raiseError(new RuntimeException())

    val saga = (for {
      _ <- bookHotel compensate cancelHotel
      _ <- failFlight compensate failCompensator
      _ <- bookCar compensate cancelCar
    } yield ()).transact.handleErrorWith(e => IO.pure(e))

    val actualError = saga.unsafeRunSync()
    actualError shouldBe expectedError
  }

}

trait TestRuntime {
  implicit val si: SagaTransactor[IO] = new SagaDefaultTransactor[IO]
  def sleep(d: FiniteDuration): IO[Unit] = IO.sleep(d)
}


object CatsSagaSpec {
  sealed trait SagaError extends RuntimeException {
    def message: String
  }
  case class FlightBookingError(message: String = "Can't book a flight")        extends SagaError
  case class HotelBookingError(message: String = "Can't book a hotel room")     extends SagaError
  case class CarBookingError(message: String = "Can't book a car")              extends SagaError
  case class PaymentFailedError(message: String = "Can't collect the payments") extends SagaError

  case class PaymentInfo(amount: Double)

  val FlightPayment = PaymentInfo(420d)
  val HotelPayment  = PaymentInfo(1448d)
  val CarPayment    = PaymentInfo(42d)

  def bookFlight: IO[PaymentInfo] = IO.pure(FlightPayment)

  def bookHotel: IO[PaymentInfo] = IO.pure(HotelPayment)

  def bookCar: IO[PaymentInfo] = IO.pure(CarPayment)

  def collectPayments(paymentInfo: PaymentInfo*): IO[Unit] = IO.pure(paymentInfo)

  def cancelFlight: IO[Unit] = IO.unit

  def cancelFlight(postAction: IO[Any]): IO[Unit] = postAction *> IO.unit

  def cancelHotel: IO[Unit] = IO.unit

  def cancelHotel(postAction: IO[Any]): IO[Unit] =
    postAction *> IO.unit

  def cancelCar: IO[Unit] = IO.unit

  def cancelCar(postAction: IO[Any]): IO[Unit] = postAction *> IO.unit

  def refundPayments(paymentInfo: PaymentInfo*): IO[Unit] = IO.pure(paymentInfo).void

}