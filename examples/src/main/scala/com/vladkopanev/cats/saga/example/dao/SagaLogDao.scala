package com.vladkopanev.cats.saga.example.dao

import java.util.UUID

import cats.effect.Bracket
import com.vladkopanev.cats.saga.example.model.{SagaInfo, SagaStep}
import doobie.util.transactor.Transactor
import io.circe.Json
import org.postgresql.util.PGobject

trait SagaLogDao[F[_]] {
  def finishSaga(sagaId: Long): F[Unit]

  def startSaga(initiator: UUID, data: Json): F[Long]

  def createSagaStep(
    name: String,
    sagaId: Long,
    result: Option[Json],
    failure: Option[String] = None
  ): F[Unit]

  def listExecutedSteps(sagaId: Long): F[List[SagaStep]]

  def listUnfinishedSagas: F[List[SagaInfo]]
}

class SagaLogDaoImpl[F[_]](xa: Transactor[F])(implicit B: Bracket[F, Throwable]) extends SagaLogDao[F] {
  import cats.syntax.all._
  import doobie._
  import doobie.implicits._
  import doobie.postgres.implicits._
  import doobie.implicits.legacy.instant._

  implicit val han = LogHandler.jdkLogHandler

  override def finishSaga(sagaId: Long): F[Unit] =
    sql"""UPDATE saga SET "finishedAt" = now() WHERE id = $sagaId""".update.run.transact(xa).void

  override def startSaga(initiator: UUID, data: Json): F[Long] =
    sql"""INSERT INTO saga("initiator", "createdAt", "finishedAt", "data", "type") 
          VALUES ($initiator, now(), null, $data, 'order')""".update
      .withUniqueGeneratedKeys[Long]("id")
      .transact(xa)

  override def createSagaStep(
    name: String,
    sagaId: Long,
    result: Option[Json],
    failure: Option[String]
  ): F[Unit] =
    sql"""INSERT INTO saga_step("sagaId", "name", "result", "finishedAt", "failure")
          VALUES ($sagaId, $name, $result, now(), $failure)""".update.run
      .transact(xa)
      .void

  override def listExecutedSteps(sagaId: Long): F[List[SagaStep]] =
    sql"""SELECT "sagaId", "name", "finishedAt", "result", "failure"
          from saga_step WHERE "sagaId" = $sagaId""".query[SagaStep].to[List].transact(xa)

  override def listUnfinishedSagas: F[List[SagaInfo]] =
    sql"""SELECT "id", "initiator", "createdAt", "finishedAt", "data", "type"
          from saga s WHERE "finishedAt" IS NULL""".query[SagaInfo].to[List].transact(xa)

  implicit lazy val JsonMeta: Meta[Json] = {
    import io.circe.parser._
    Meta.Advanced
      .other[PGobject]("jsonb")
      .timap[Json](
        pgObj => parse(pgObj.getValue).fold(e => sys.error(e.message), identity)
      )(
        json => {
          val pgObj = new PGobject
          pgObj.setType("jsonb")
          pgObj.setValue(json.noSpaces)
          pgObj
        }
      )
  }

}
