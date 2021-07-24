package com.vladkopanev.cats.saga.example.client

import java.util.UUID
import cats.effect.kernel.Async
import cats.syntax.all._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait OrderServiceClient[F[_]] {

  def closeOrder(userId: UUID, orderId: BigInt, traceId: String): F[Unit]

  def reopenOrder(userId: UUID, orderId: BigInt, traceId: String): F[Unit]
}

class OrderServiceClientStub[F[_]: Async](logger: Logger[F],
                                          maxRequestTimeout: Int,
                                          flaky: Boolean) extends OrderServiceClient[F] {
  import FUtil._

  override def closeOrder(userId: UUID, orderId: BigInt, traceId: String): F[Unit] =
    for {
      _ <- randomSleep(maxRequestTimeout)
      _ <- randomFail("closeOrder").whenA(flaky)
      _ <- logger.info(s"Order #$orderId closed")
    } yield ()

  override def reopenOrder(userId: UUID, orderId: BigInt, traceId: String): F[Unit] =
    for {
      _ <- randomSleep(maxRequestTimeout)
      _ <- randomFail("reopenOrder").whenA(flaky)
      _ <- logger.info(s"Order #$orderId reopened")
    } yield ()
}

object OrderServiceClientStub {

  def apply[F[_]: Async](maxRequestTimeout: Int, flaky: Boolean): F[OrderServiceClientStub[F]] =
    Slf4jLogger.create[F].map(new OrderServiceClientStub(_, maxRequestTimeout, flaky))
}
