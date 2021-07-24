package com.vladkopanev.cats.saga.example.client

import cats.effect.Sync
import cats.effect.kernel.{Async, Temporal}
import cats.syntax.all._

import scala.concurrent.duration._
import scala.util.Random

object FUtil {
  def randomSleep[F[_]: Async](maxTimeout: Int): F[Unit] = {
    for {
      randomSeconds <- Sync[F].delay(Random.nextInt(maxTimeout))
      _             <- Temporal[F].sleep(randomSeconds.seconds)
    } yield ()
  }

  def randomFail[F[_]: Async](operationName: String): F[Unit] =
    for {
      randomInt <- Sync[F].delay(Random.nextInt(100))
      _         <- if (randomInt % 10 == 0) Sync[F].raiseError[Unit](new RuntimeException(s"Failed to execute $operationName"))
      else Sync[F].unit
    } yield ()

}
