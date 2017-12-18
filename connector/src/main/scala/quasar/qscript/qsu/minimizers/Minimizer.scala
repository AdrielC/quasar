/*
 * Copyright 2014–2017 SlamData Inc.
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

package quasar.qscript.qsu
package minimizers

import quasar.{NameGenerator, Planner}, Planner.PlannerErrorME
import quasar.contrib.scalaz.MonadState_
import slamdata.Predef._

import scalaz.Monad

trait Minimizer[T[_[_]]] extends QSUTTypes[T] {
  import MinimizeAutoJoins.MinimizationState

  def couldApplyTo(candidates: List[QSUGraph]): Boolean

  def extract[
      G[_]: Monad: NameGenerator: PlannerErrorME: MonadState_[?[_], RevIdx]: MonadState_[?[_], MinimizationState[T]]](
      qgraph: QSUGraph): Option[(QSUGraph, (QSUGraph, FreeMap) => G[QSUGraph])]

  // the first component of the tuple is the rewrite target on any provenance association
  // the second component is the root of the resulting graph
  def apply[
      G[_]: Monad: NameGenerator: PlannerErrorME: MonadState_[?[_], RevIdx]: MonadState_[?[_], MinimizationState[T]]](
      qgraph: QSUGraph,
      singleSource: QSUGraph,
      candidates: List[QSUGraph],
      fm: FreeMapA[Int]): G[Option[(QSUGraph, QSUGraph)]]
}
