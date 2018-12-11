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

package quasar.impl.datasource.local

import slamdata.Predef._

import quasar.api.resource.{ResourceName, ResourcePath}
import quasar.common.data.RValue
import quasar.concurrent.BlockingContext
import quasar.connector.{DatasourceSpec, ResourceError}
import quasar.contrib.scalaz.MonadError_
import quasar.qscript.InterpretedRead

import java.nio.file.Paths
import scala.concurrent.ExecutionContext

import cats.effect.IO
import fs2.Stream
import shims._

abstract class LocalDatasourceSpec
    extends DatasourceSpec[IO, Stream[IO, ?]] {

  implicit val ioMonadResourceErr: MonadError_[IO, ResourceError] =
    MonadError_.facet[IO](ResourceError.throwableP)

  implicit val tmr = IO.timer(ExecutionContext.Implicits.global)

  override def datasource
      : quasar.connector.Datasource[IO, Stream[IO, ?], InterpretedRead[ResourcePath], quasar.connector.QueryResult[IO]]

  val nonExistentPath =
    ResourcePath.root() / ResourceName("non") / ResourceName("existent")

  def gatherMultiple[A](g: Stream[IO, A]) = g.compile.toList

  "listing a file path returns none" >>* {
    datasource
      .prefixedChildPaths(ResourcePath.root() / ResourceName("smallZips.data"))
      .map(_ must beNone)
  }

  "returns data from a nonempty file" >>* {
    datasource
      .evaluate(InterpretedRead(ResourcePath.root() / ResourceName("smallZips.data"), List()))
      .flatMap(_.data.compile.fold(0)((c, _) => c + 1))
      .map(_ must be_>(0))
  }
}

object LocalDatasourceSpec extends LocalDatasourceSpec {
  val blockingPool = BlockingContext.cached("local-datasource-spec")

  def datasource =
    LocalDatasource[IO](Paths.get("./it/src/main/resources/tests"), 1024, blockingPool)
}

object LocalParsedDatasourceSpec extends LocalDatasourceSpec {
  val blockingPool = BlockingContext.cached("local-parsed-datasource-spec")

  def datasource =
    LocalParsedDatasource[IO, RValue](Paths.get("./it/src/main/resources/tests"), 1024, blockingPool)
}
