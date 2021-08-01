package com.vladkopanev.cats.saga.example

import cats.effect.{ExitCode, IO, IOApp}
import com.vladkopanev.cats.saga.{SagaDefaultTransactor, SagaTransactor}
import com.vladkopanev.cats.saga.example.client.{LoyaltyPointsServiceClientStub, OrderServiceClientStub, PaymentServiceClientStub}
import com.vladkopanev.cats.saga.example.dao.SagaLogDaoImpl
import com.vladkopanev.cats.saga.example.endpoint.SagaEndpoint
import doobie.util.transactor.Transactor
import org.http4s.blaze.server.BlazeServerBuilder

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object SagaApp extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    val flakyClient         = sys.env.getOrElse("FLAKY_CLIENT", "false").toBoolean
    val clientMaxReqTimeout = sys.env.getOrElse("CLIENT_MAX_REQUEST_TIMEOUT_SEC", "10").toInt
    val sagaMaxReqTimeout   = sys.env.getOrElse("SAGA_MAX_REQUEST_TIMEOUT_SEC", "12").toInt

    val ec = ExecutionContext.fromExecutor(
      Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors())
    )
    implicit val sagaInterpreter: SagaTransactor[IO] = new SagaDefaultTransactor[IO]

    (for {
      paymentService <- PaymentServiceClientStub[IO](clientMaxReqTimeout, flakyClient)
      loyaltyPoints  <- LoyaltyPointsServiceClientStub[IO](clientMaxReqTimeout, flakyClient)
      orderService   <- OrderServiceClientStub[IO](clientMaxReqTimeout, flakyClient)
      xa             = Transactor.fromDriverManager[IO]("org.postgresql.Driver", "jdbc:postgresql:Saga", "postgres", "root")
      logDao         = new SagaLogDaoImpl(xa)
      orderSEC       <- OrderSagaCoordinatorImpl(paymentService, loyaltyPoints, orderService, logDao, sagaMaxReqTimeout)
      app            = new SagaEndpoint(orderSEC).service
      _              <- orderSEC.recoverSagas.start
      _              <- BlazeServerBuilder[IO](ec).bindHttp(8042).withHttpApp(app).serve.compile.drain
    } yield ()).attempt.flatMap {
      case Left(e) => IO(println(s"Saga Coordinator fails with error $e, stopping server...")).as(ExitCode.Error)
      case _       => IO(println(s"Saga Coordinator finished successfully, stopping server...")).as(ExitCode.Success)
    }
  }

}
