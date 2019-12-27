/*
 * Copyright 2014–2019 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.impl.datasources

import slamdata.Predef._

import quasar.{RateLimiter, RenderTreeT}
import quasar.api.datasource._
import quasar.api.datasource.DatasourceError._
import quasar.api.resource._
import quasar.impl.DatasourceModule
import quasar.impl.datasource.MonadCreateErr
import quasar.impl.IncompatibleModuleException.linkDatasource
import quasar.connector.{Datasource, MonadResourceErr, QueryResult}
import quasar.contrib.scalaz._
import quasar.qscript.{InterpretedRead, QScriptEducated, MonadPlannerErr}

import argonaut.Json
import argonaut.Argonaut.jEmptyObject

import fs2.Stream

import cats.{Applicative, ApplicativeError, MonadError}
import cats.effect.{Resource, ConcurrentEffect, ContextShift, Sync, Timer}
import cats.syntax.applicative._
import cats.syntax.bifunctor._
import cats.instances.either._
import matryoshka.{BirecursiveT, EqualT, ShowT}
import scalaz.{ISet, ~>}


import scala.concurrent.ExecutionContext

trait DatasourceModules[T[_[_]], F[_], G[_], I, C, R, P <: ResourcePathType] { self =>
  def create(i: I, ref: DatasourceRef[C]): Resource[F, ManagedDatasource[T, F, G, R, P]]
  def sanitizeRef(inp: DatasourceRef[C]): DatasourceRef[C]
  def supportedTypes: F[ISet[DatasourceType]]

  def withMiddleware[H[_], S, Q <: ResourcePathType](
    f: (I, ManagedDatasource[T, F, G, R, P]) => F[ManagedDatasource[T, F, H, S, Q]])(
    implicit
    AF: Applicative[F])
    : DatasourceModules[T, F, H, I, C, S, Q] =
    new DatasourceModules[T, F, H, I, C, S, Q] {
      def create(i: I, ref: DatasourceRef[C]): Resource[F, ManagedDatasource[T, F, H, S, Q]] =
        self.create(i, ref).evalMap { (mds: ManagedDatasource[T, F, G, R, P]) => f(i, mds) }
      def sanitizeRef(inp: DatasourceRef[C]): DatasourceRef[C] =
        self.sanitizeRef(inp)
      def supportedTypes: F[ISet[DatasourceType]] =
        self.supportedTypes
    }

  def withFinalizer(f: (I, ManagedDatasource[T, F, G, R, P]) => F[Unit])(implicit F: Sync[F]): DatasourceModules[T, F, G, I, C, R, P] =
    new DatasourceModules[T, F, G, I, C, R, P] {
      def create(i: I, ref: DatasourceRef[C]): Resource[F, ManagedDatasource[T, F, G, R, P]] =
        self.create(i, ref)
      /*flatMap { (mds: ManagedDatasource[T, F, G, R, P]) =>
          Resource.make(mds)(x => f(i, x))
        }*/
      def sanitizeRef(inp: DatasourceRef[C]): DatasourceRef[C] =
        self.sanitizeRef(inp)
      def supportedTypes: F[ISet[DatasourceType]] =
        self.supportedTypes
    }

  def widenPathType[PP >: P <: ResourcePathType](implicit AF: Applicative[F]): DatasourceModules[T, F, G, I, C, R, PP] =
    new DatasourceModules[T, F, G, I, C, R, PP] {
      def create(i: I, ref: DatasourceRef[C]): Resource[F, ManagedDatasource[T, F, G, R, PP]] =
        self.create(i, ref) map { ManagedDatasource.widenPathType[T, F, G, R, P, PP](_) }
      def sanitizeRef(inp: DatasourceRef[C]): DatasourceRef[C] =
        self.sanitizeRef(inp)
      def supportedTypes: F[ISet[DatasourceType]] =
        self.supportedTypes
    }

}

object DatasourceModules {
  type Modules[T[_[_]], F[_], I] = DatasourceModules[T, F, Stream[F, ?], I, Json, QueryResult[F], ResourcePathType.Physical]
  type MDS[T[_[_]], F[_]] = ManagedDatasource[T, F, Stream[F, ?], QueryResult[F], ResourcePathType.Physical]

  def apply[
      T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT,
      F[_]: ConcurrentEffect: ContextShift: Timer: MonadResourceErr: MonadCreateErr: MonadPlannerErr,
      I](
      modules: List[DatasourceModule],
      rateLimiter: RateLimiter[F])(
      implicit
      ec: ExecutionContext)
      : Modules[T, F, I] = {
    lazy val moduleSet: ISet[DatasourceType] = ISet.fromList(modules.map(_.kind))
    lazy val moduleMap: Map[DatasourceType, DatasourceModule] = Map(modules.map(ds => (ds.kind, ds)):_*)

    new DatasourceModules[T, F, Stream[F, ?], I, Json, QueryResult[F], ResourcePathType.Physical] {
      def create(i: I, ref: DatasourceRef[Json]): Resource[F, MDS[T, F]] = moduleMap.get(ref.kind) match {
        case None =>
          Resource.liftF(MonadError_[F, CreateError[Json]].raiseError(DatasourceUnsupported(ref.kind, moduleSet)))
        case Some(module) => module match {
          case DatasourceModule.Lightweight(lw) =>
            handleInitErrors(module.kind, lw.lightweightDatasource[F](ref.config, rateLimiter))
              .map(ManagedDatasource.lightweight[T](_))
          case DatasourceModule.Heavyweight(hw) =>
            handleInitErrors(module.kind, hw.heavyweightDatasource[T, F](ref.config))
              .map(ManagedDatasource.heavyweight(_))
        }
      }
      def sanitizeRef(inp: DatasourceRef[Json]): DatasourceRef[Json] = moduleMap.get(inp.kind) match {
        case None => inp.copy(config = jEmptyObject)
        case Some(x) => inp.copy(config = x.sanitizeConfig(inp.config))
      }

      def supportedTypes: F[ISet[DatasourceType]] = {
        moduleSet.pure[F]
      }
    }
  }

  private def handleInitErrors[F[_]: MonadCreateErr: MonadError[?[_], Throwable], A](
      kind: DatasourceType,
      res: Resource[F, Either[InitializationError[Json], A]])
      : Resource[F, A] = {
    import quasar.contrib.cats.monadError.monadError_CatsMonadError

    implicit val merr: MonadError[F, CreateError[Json]] =
      monadError_CatsMonadError[F, CreateError[Json]](
        MonadError[F, Throwable], MonadError_[F, CreateError[Json]])

    val rmerr = MonadError[Resource[F, ?], CreateError[Json]]

    rmerr.rethrow(rmerr.map(handleLinkageError(kind, res))(_.leftMap(ie => ie: CreateError[Json])))
  }

  private def handleLinkageError[F[_]: ApplicativeError[?[_], Throwable], A](kind: DatasourceType, fa: => F[A]): F[A] =
    linkDatasource(kind, fa)
}
