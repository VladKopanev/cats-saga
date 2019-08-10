package com.vladkopanev.cats.saga.example.client

import java.util.UUID

import cats.Monad
import cats.effect.Sync
import cats.syntax.all._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

trait OrderServiceClient[F[_]] {

  def closeOrder(userId: UUID, orderId: BigInt, traceId: String): F[Unit]

  def reopenOrder(userId: UUID, orderId: BigInt, traceId: String): F[Unit]
}

class OrderServiceClientStub[F[_]: Monad](logger: Logger[F],
                                          randomUtil: FUtil[F],
                                          maxRequestTimeout: Int,
                                          flaky: Boolean) extends OrderServiceClient[F] {
  import randomUtil._

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

  def apply[F[_]: Sync](randomUtil: FUtil[F], maxRequestTimeout: Int, flaky: Boolean): F[OrderServiceClientStub[F]] =
    Slf4jLogger.create[F].map(new OrderServiceClientStub(_, randomUtil, maxRequestTimeout, flaky))
}
