package com.vladkopanev.cats.saga.example.client

import java.util.UUID

import cats.Monad
import cats.effect.Sync
import cats.syntax.all._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

trait PaymentServiceClient[F[_]] {

  def collectPayments(userId: UUID, amount: BigDecimal, traceId: String): F[Unit]

  def refundPayments(userId: UUID, amount: BigDecimal, traceId: String): F[Unit]
}

class PaymentServiceClientStub[F[_]: Monad](logger: Logger[F],
                                            randomUtil: FUtil[F],
                                            maxRequestTimeout: Int,
                                            flaky: Boolean) extends PaymentServiceClient[F] {
  import randomUtil._

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

  def apply[F[_]: Sync](randomUtil: FUtil[F], maxRequestTimeout: Int, flaky: Boolean): F[PaymentServiceClient[F]] =
    Slf4jLogger.create[F].map(new PaymentServiceClientStub(_, randomUtil, maxRequestTimeout, flaky))
}
