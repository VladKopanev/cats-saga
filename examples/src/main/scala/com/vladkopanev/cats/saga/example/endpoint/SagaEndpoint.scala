package com.vladkopanev.cats.saga.example.endpoint

import cats.effect.Concurrent
import cats.syntax.all._
import com.vladkopanev.cats.saga.example.OrderSagaCoordinator
import com.vladkopanev.cats.saga.example.model.OrderInfo
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.{HttpApp, HttpRoutes}

final class SagaEndpoint[F[_]: Concurrent](orderSagaCoordinator: OrderSagaCoordinator[F]) extends Http4sDsl[F] {

  private implicit val decoder = jsonOf[F, OrderInfo]

  val service: HttpApp[F] = HttpRoutes
    .of[F] {
      case req @ POST -> Root / "saga" / "finishOrder" =>
        for {
          OrderInfo(userId, orderId, money, bonuses) <- req.as[OrderInfo]
          resp <- orderSagaCoordinator
                   .runSaga(userId, orderId, money, bonuses, None)
                   .attempt
                   .flatMap {
                     case Left(fail) => InternalServerError(fail.getMessage)
                     case Right(_)   => Ok("Saga submitted")
                   }
        } yield resp
    }
    .orNotFound
}
