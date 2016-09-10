/*
 * Copyright 2014–2016 SlamData Inc.
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

package quasar.qscript

import quasar.Predef._
import quasar.fp._
import quasar.qscript.MapFunc._

import matryoshka._
import scalaz._
import simulacrum.typeclass

@typeclass trait Normalizable[F[_]] {
  def normalize: F ~> F

  def normalizeMapFunc[T[_[_]]: Recursive: Corecursive: EqualT, A](fm: Free[MapFunc[T, ?], A]):
      Free[MapFunc[T, ?], A] =
    freeTransCata[T, MapFunc[T, ?], MapFunc[T, ?], A, A](fm)(
      repeatedly(MapFunc.normalize[T, A]) compose repeatedly(MapFunc.foldConstant[T, A]))
}

trait NormalizableInstances {
  implicit def const[A]: Normalizable[Const[A, ?]] =
    new Normalizable[Const[A, ?]] {
      def normalize = new (Const[A, ?] ~> Const[A, ?]) {
        def apply[B](const: Const[A, B]) = const
      }
    }

  implicit def coproduct[F[_], G[_]](
    implicit F: Normalizable[F], G: Normalizable[G]):
      Normalizable[Coproduct[F, G, ?]] =
    new Normalizable[Coproduct[F, G, ?]] {
      def normalize = new (Coproduct[F, G, ?] ~> Coproduct[F, G, ?]) {
        def apply[A](sp: Coproduct[F, G, A]) =
          Coproduct(sp.run.bimap(F.normalize(_), G.normalize(_)))
      }
    }
}

object Normalizable extends NormalizableInstances

