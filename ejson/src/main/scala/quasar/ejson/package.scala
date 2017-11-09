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

package quasar

import slamdata.Predef._
import quasar.fp.ski._

import java.lang.String
import scala.Predef.implicitly
import scala.Unit
import scala.collection.immutable.{List, ListMap}
import scala.math.{BigDecimal, BigInt}

import monocle.{Iso, Prism}
import scalaz._

package object ejson {
  def arr[A] =
    Prism.partial[Common[A], List[A]] { case Arr(a) => a } (Arr(_))

  def bool[A] =
    Prism.partial[Common[A], Boolean] { case Bool(b) => b } (Bool(_))

  def dec[A] =
    Prism.partial[Common[A], BigDecimal] { case Dec(bd) => bd } (Dec(_))

  def nul[A] =
    Prism.partial[Common[A], Unit] { case Null() => () } (κ(Null()))

  def str[A] =
    Prism.partial[Common[A], String] { case Str(s) => s } (Str(_))

  def obj[A] =
    Iso[Obj[A], ListMap[String, A]](_.value)(Obj(_))

  def byte[A] =
    Prism.partial[Extension[A], scala.Byte] { case Byte(b) => b } (Byte(_))

  def char[A] =
    Prism.partial[Extension[A], scala.Char] { case Char(c) => c } (Char(_))

  def int[A] =
    Prism.partial[Extension[A], BigInt] { case Int(i) => i } (Int(_))

  def map[A] =
    Prism.partial[Extension[A], List[(A, A)]] { case Map(m) => m } (Map(_))

  def meta[A] =
    Prism.partial[Extension[A], (A, A)] {
      case Meta(v, m) => (v, m)
    } ((Meta(_: A, _: A)).tupled)

  /** For _strict_ JSON, you want something like `Obj[Mu[Json]]`.
    */
  type Json[A]    = Coproduct[Obj, Common, A]
  val ObjJson     = implicitly[Obj :<: Json]
  val CommonJson  = implicitly[Common :<: Json]

  type EJson[A]   = Coproduct[Extension, Common, A]
  val ExtEJson    = implicitly[Extension :<: EJson]
  val CommonEJson = implicitly[Common :<: EJson]
}
