package com.vladkopanev.cats.saga.example.client

import java.util.UUID

import cats.Monad
import cats.effect.Sync
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import cats.syntax.all._

trait LoyaltyPointsServiceClient[F[_]] {

  def assignLoyaltyPoints(userId: UUID, amount: Double, traceId: String): F[Unit]

  def cancelLoyaltyPoints(userId: UUID, amount: Double, traceId: String): F[Unit]
}

class LoyaltyPointsServiceClientStub[F[_]: Monad](logger: Logger[F],
                                                  randomUtil: FUtil[F],
                                                  maxRequestTimeout: Int,
                                                  flaky: Boolean) extends LoyaltyPointsServiceClient[F] {
  import randomUtil._

  override def assignLoyaltyPoints(userId: UUID, amount: Double, traceId: String): F[Unit] =
    for {
      _ <- randomSleep(maxRequestTimeout)
      _ <- randomFail("assignLoyaltyPoints").whenA(flaky)
      _ <- logger.info(s"Loyalty points assigned to user $userId")
    } yield ()

  override def cancelLoyaltyPoints(userId: UUID, amount: Double, traceId: String): F[Unit] =
    for {
      _ <- randomSleep(maxRequestTimeout)
      _ <- randomFail("cancelLoyaltyPoints").whenA(flaky)
      _ <- logger.info(s"Loyalty points canceled for user $userId")
    } yield ()

}

object LoyaltyPointsServiceClientStub {

  def apply[F[_]: Sync](randomUtil: FUtil[F],
                        maxRequestTimeout: Int,
                        flaky: Boolean): F[LoyaltyPointsServiceClientStub[F]] =
    Slf4jLogger.create[F].map(new LoyaltyPointsServiceClientStub(_, randomUtil, maxRequestTimeout, flaky))
}
