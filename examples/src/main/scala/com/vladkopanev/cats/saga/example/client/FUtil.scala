package com.vladkopanev.cats.saga.example.client

import cats.{ApplicativeError, Monad}
import cats.effect.{Async, Concurrent, ContextShift, Sync, Timer}
import cats.syntax.all._

import scala.util.Random
import scala.concurrent.duration._

class FUtil[F[_]: Concurrent: Timer: ContextShift] {
  def randomSleep(maxTimeout: Int): F[Unit] =
    for {
      randomSeconds <- Sync[F].delay(Random.nextInt(maxTimeout))
      _             <- Timer[F].sleep(randomSeconds.seconds)
    } yield ()

  def randomFail(operationName: String): F[Unit] =
    for {
      randomInt <- Sync[F].delay(Random.nextInt(100))
      _         <- if (randomInt % 10 == 0) Sync[F].raiseError[Unit](new RuntimeException(s"Failed to execute $operationName"))
      else Sync[F].unit
    } yield ()

}
