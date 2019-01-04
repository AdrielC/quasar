/*
 * Copyright 2014–2018 SlamData Inc.
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

package quasar.connector.datasource

import slamdata.Predef.{Boolean, Option}

import quasar.api.datasource.DatasourceType
import quasar.api.resource._
import quasar.connector._

import scalaz.{Applicative, ApplicativePlus}
import scalaz.syntax.applicativePlus._
import scalaz.syntax.equal._

/** A Datasource providing access to a single resource.
  *
  * @param tpe the type of this datasource
  * @param location the location of the resource
  * @param resource an effect producing the resource
  */
final class SingletonDatasource[F[_]: MonadResourceErr, G[_]: ApplicativePlus, R] private (
    tpe: DatasourceType,
    location: ResourcePath,
    resource: F[R])(
    implicit F: Applicative[F])
    extends Datasource[F, G, ResourcePath, R] {

  val kind: DatasourceType = tpe

  def evaluate(path: ResourcePath): F[R] = {
    if (path === location)
      resource
    else if (location.relativeTo(path).isDefined)
      MonadResourceErr[F].raiseError(ResourceError.notAResource(path))
    else
      MonadResourceErr[F].raiseError(ResourceError.pathNotFound(path))
  }

  def pathIsResource(path: ResourcePath): F[Boolean] =
    F.point(path === location)

  def prefixedChildPaths(prefixPath: ResourcePath)
      : F[Option[G[(ResourceName, ResourcePathType)]]] =
    F.point(location.relativeTo(prefixPath) map {
      case ResourcePath.Root => mempty[G, (ResourceName, ResourcePathType)]
      case h /: ResourcePath.Root => (ResourceName(h), ResourcePathType.leafResource).point[G]
      case h /: _ => (ResourceName(h), ResourcePathType.prefix).point[G]
    })
}

object SingletonDatasource {
  def apply[F[_]: Applicative: MonadResourceErr, G[_]: ApplicativePlus, R](
    tpe: DatasourceType,
    location: ResourcePath,
    resource: F[R])
    : Datasource[F, G, ResourcePath, R] =
  new SingletonDatasource[F, G, R](tpe, location, resource)
}
