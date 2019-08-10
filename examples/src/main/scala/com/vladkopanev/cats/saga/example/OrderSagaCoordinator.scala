package com.vladkopanev.cats.saga.example

import java.util.UUID

import cats.Parallel
import cats.effect.{Concurrent, IO, Sync, Timer}
import cats.syntax.all._
import cats.temp.par.Par
import com.vladkopanev.cats.saga.example.client.{FUtil, LoyaltyPointsServiceClient, OrderServiceClient, PaymentServiceClient}
import com.vladkopanev.cats.saga.example.dao.SagaLogDao
import com.vladkopanev.cats.saga.example.model.{OrderSagaData, OrderSagaError, SagaStep}
import io.chrisdavenport.log4cats.StructuredLogger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import retry.{RetryPolicies, Sleep}

import scala.concurrent.duration._

trait OrderSagaCoordinator[F[_]] {
  def runSaga(userId: UUID, orderId: BigInt, money: BigDecimal, bonuses: Double, sagaIdOpt: Option[Long]): F[Unit]

  def recoverSagas: F[Unit]
}

class OrderSagaCoordinatorImpl[F[_]](
  paymentServiceClient: PaymentServiceClient[F],
  loyaltyPointsServiceClient: LoyaltyPointsServiceClient[F],
  orderServiceClient: OrderServiceClient[F],
  sagaLogDao: SagaLogDao[F],
  maxRequestTimeout: Int,
  logger: StructuredLogger[F]
)(implicit M: Concurrent[F], P: Par[F], T: Timer[F], S: Sleep[F]) extends OrderSagaCoordinator[F] {

  import com.vladkopanev.cats.saga.Saga._

  def runSaga(
    userId: UUID,
    orderId: BigInt,
    money: BigDecimal,
    bonuses: Double,
    sagaIdOpt: Option[Long]
  ): F[Unit] = {

    def mkSagaRequest(
      request: F[Unit],
      sagaId: Long,
      stepName: String,
      executedSteps: List[SagaStep],
      compensating: Boolean = false
    ) = {
      val maybeError = executedSteps
        .find(step => step.name == stepName && !compensating)
        .flatMap(_.failure)
        .map(new OrderSagaError(_))
        .pure[F]

        maybeError.flatMap(_.fold(M.unit)(M.raiseError)) *>
          Concurrent.timeout(request, maxRequestTimeout.seconds)
          .attempt
          .flatMap {
            case Left(e) => sagaLogDao.createSagaStep(stepName, sagaId, result = None, failure = Some(e.getMessage))
            case _ => sagaLogDao.createSagaStep(stepName, sagaId, result = None)
          }
          .whenA(!executedSteps.exists(_.name == stepName))
    }

    def collectPayments(executed: List[SagaStep], sagaId: Long) = mkSagaRequest(
      paymentServiceClient.collectPayments(userId, money, sagaId.toString),
      sagaId,
      "collectPayments",
      executed
    )

    def assignLoyaltyPoints(executed: List[SagaStep], sagaId: Long) = mkSagaRequest(
      loyaltyPointsServiceClient.assignLoyaltyPoints(userId, bonuses, sagaId.toString),
      sagaId,
      "assignLoyaltyPoints",
      executed
    )

    def closeOrder(executed: List[SagaStep], sagaId: Long) =
      mkSagaRequest(orderServiceClient.closeOrder(userId, orderId, sagaId.toString), sagaId, "closeOrder", executed)

    def refundPayments(executed: List[SagaStep], sagaId: Long) = mkSagaRequest(
      paymentServiceClient.refundPayments(userId, money, sagaId.toString),
      sagaId,
      "refundPayments",
      executed,
      compensating = true
    )

    def cancelLoyaltyPoints(executed: List[SagaStep], sagaId: Long) = mkSagaRequest(
      loyaltyPointsServiceClient.cancelLoyaltyPoints(userId, bonuses, sagaId.toString),
      sagaId,
      "cancelLoyaltyPoints",
      executed,
      compensating = true
    )

    def reopenOrder(executed: List[SagaStep], sagaId: Long) =
      mkSagaRequest(
        orderServiceClient.reopenOrder(userId, orderId, sagaId.toString),
        sagaId,
        "reopenOrder",
        executed,
        compensating = true
      )

    val expSchedule = RetryPolicies.exponentialBackoff[F](1.second)
    def buildSaga(sagaId: Long, executedSteps: List[SagaStep]) =
      for {
        _ <- collectPayments(executedSteps, sagaId) retryableCompensate (refundPayments(executedSteps, sagaId), expSchedule)
        _ <- assignLoyaltyPoints(executedSteps, sagaId) retryableCompensate (cancelLoyaltyPoints(executedSteps, sagaId), expSchedule)
        _ <- closeOrder(executedSteps, sagaId) retryableCompensate (reopenOrder(executedSteps, sagaId), expSchedule)
      } yield ()

    import io.circe.syntax._

    val mdcLog = wrapMDC(logger, userId, orderId, sagaIdOpt)
    val data   = OrderSagaData(userId, orderId, money, bonuses).asJson

    for {
      _        <- mdcLog.info("Saga execution started")
      sagaId   <- sagaIdOpt.fold(sagaLogDao.startSaga(userId, data))(M.pure)
      executed <- sagaLogDao.listExecutedSteps(sagaId)
      _ <- buildSaga(sagaId, executed).transact.attempt.flatMap {
            case Left(_: OrderSagaError) => sagaLogDao.finishSaga(sagaId)
            case Left(_)                 => M.unit
            case Right(_)                => sagaLogDao.finishSaga(sagaId)
          }
      _ <- mdcLog.info("Saga execution finished")
    } yield ()

  }

  override def recoverSagas: F[Unit] = {
    import cats.instances.all._
    import cats.temp.par._
    for {
      _     <- logger.info("Sagas recovery stared")
      sagas <- sagaLogDao.listUnfinishedSagas
      _     <- logger.info(s"Found unfinished sagas: $sagas")
      _ <- sagas.parTraverse { sagaInfo =>
            M.fromEither(sagaInfo.data.as[OrderSagaData]).flatMap {
              case OrderSagaData(userId, orderId, money, bonuses) =>
                runSaga(userId, orderId, money, bonuses, Some(sagaInfo.id)).recover {
                  case e: OrderSagaError => M.unit
                }
            }
          }
      _ <- logger.info("Sagas recovery finished")
    } yield ()
  }

  private def wrapMDC(logger: StructuredLogger[F], userId: UUID, orderId: BigInt, sagaIdOpt: Option[Long]) =
    StructuredLogger.withContext(logger)(
      Map("userId" -> userId.toString, "orderId" -> orderId.toString, "sagaId" -> sagaIdOpt.toString)
    )
}

object OrderSagaCoordinatorImpl {

  def apply[F[_]: Sync: Concurrent: Timer: Sleep](
    paymentServiceClient: PaymentServiceClient[F],
    loyaltyPointsServiceClient: LoyaltyPointsServiceClient[F],
    orderServiceClient: OrderServiceClient[F],
    sagaLogDao: SagaLogDao[F],
    maxRequestTimeout: Int
  )(implicit P: Par[F]): F[OrderSagaCoordinatorImpl[F]] =
    Slf4jLogger
      .create[F]
      .map(
        new OrderSagaCoordinatorImpl(
          paymentServiceClient,
          loyaltyPointsServiceClient,
          orderServiceClient,
          sagaLogDao,
          maxRequestTimeout,
          _
        )
      )
}
