package com.vladkopanev.cats.saga.example.client

import java.util.UUID
import cats.effect.kernel.Async
import cats.syntax.all._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait PaymentServiceClient[F[_]] {

  def collectPayments(userId: UUID, amount: BigDecimal, traceId: String): F[Unit]

  def refundPayments(userId: UUID, amount: BigDecimal, traceId: String): F[Unit]
}

class PaymentServiceClientStub[F[_]: Async](logger: Logger[F],
                                            maxRequestTimeout: Int,
                                            flaky: Boolean) extends PaymentServiceClient[F] {
  import FUtil._

  override def collectPayments(userId: UUID, amount: BigDecimal, traceId: String): F[Unit] =
    for {
      _ <- randomSleep(maxRequestTimeout)
      _ <- randomFail("collectPayments").whenA(flaky)
      _ <- logger.info(s"Payments collected from user #$userId")
    } yield ()

  override def refundPayments(userId: UUID, amount: BigDecimal, traceId: String): F[Unit] =
    for {
      _ <- randomSleep(maxRequestTimeout)
      _ <- randomFail("refundPayments").whenA(flaky)
      _ <- logger.info(s"Payments refunded to user #$userId")
    } yield ()
}

object PaymentServiceClientStub {

  def apply[F[_]: Async](maxRequestTimeout: Int, flaky: Boolean): F[PaymentServiceClient[F]] =
    Slf4jLogger.create.map(new PaymentServiceClientStub(_, maxRequestTimeout, flaky))
}
