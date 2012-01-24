/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.querio
package daze

import blueeyes.json._

import com.reportgrid.analytics.Path
import com.reportgrid.yggdrasil._
import com.reportgrid.util._

import org.specs2.mutable._

import scalaz._
import scalaz.effect._
import scalaz.iteratee._
import scalaz.std.set._

object EvaluatorSpecs extends Specification with Evaluator {
  import JsonAST._
  import Function._
  import IterateeT._
  
  import dag._
  import instructions._

  override val ops: DatasetEnumFunctions = new OperationalDatasetEnumFunctions {
    def flatMap[X, E1, E2, G[_]: Monad](enum: DatasetEnum[X, E1, G])(f: E1 => DatasetEnum[X, E2, G]): DatasetEnum[X, E2, G] = sys.error("")
    def sort[X, F[_]](enum: DatasetEnum[X, SEvent, F])(implicit order: Order[SEvent], monad: Monad[F]): DatasetEnum[X, SEvent, F] = sys.error("")
  }
  
  "bytecode evaluator" should {
    "evaluate simple two-value multiplication" in {
      val line = Line(0, "")
      val input = Join(line, Map2Cross(Mul), Root(line, PushNum("6")), Root(line, PushNum("7")))
      val result = consumeEval(input)
      
      result must haveSize(1)
      
      val result2 = result collect {
        case (_, SDecimal(d)) => d.toInt
      }
      
      result2 must contain(42)
    }
  }
  
  override object query extends StorageEngineQueryAPI {
    private var pathIds = Map[Path, Int]()
    private var currentId = 0
    
    def fullProjection[X](path: Path): DatasetEnum[X, SEvent, IO] =
      DatasetEnum(readJSON[X](path))
    
    private def readJSON[X](path: Path) = {
      val src = scala.io.Source.fromInputStream(getClass getResourceAsStream path.elements.mkString("/", "/", ".json"))
      val stream = Stream from 0 map scaleId(path) zip (src.getLines map parseJSON toStream) map tupled(wrapSEvent)
      Iteratee.enumPStream[X, SEvent, IO](stream)
    }
    
    private def scaleId(path: Path)(seed: Int): Long = {
      val scalar = synchronized {
        if (!(pathIds contains path)) {
          pathIds += (path -> currentId)
          currentId += 1
        }
        
        pathIds(path)
      }
      
      (scalar.toLong << 32) | seed
    }
    
    private def parseJSON(str: String): JValue =
      JsonParser parse str
    
    private def wrapSEvent(id: Long, value: JValue): SEvent =
      (Vector(id), wrapSValue(value))
    
    private def wrapSValue(value: JValue): SValue = new SValue {
      def fold[A](
          obj: Map[String, SValue] => A,
          arr: Vector[SValue] => A,
          str: String => A,
          bool: Boolean => A,
          long: Long => A,
          double: Double => A,
          num: BigDecimal => A,
          nul: => A): A = value match {
            
        case JObject(fields) => {
          val pairs = fields map {
            case JField(key, value) => (key, wrapSValue(value))
          }
          
          obj(Map(pairs: _*))
        }
        
        case JArray(values) => arr(Vector(values map wrapSValue: _*))
        
        case JString(s) => str(s)
        
        case JBool(b) => bool(b)
        
        case JInt(i) => long(i.toLong)      // TODO I hate my life
        
        case JDouble(d) => double(d)
        
        case JNull => nul
        
        case JNothing => sys.error("Hit JNothing")
      }
    }
  }
  
  private def consumeEval(graph: DepGraph): Set[SEvent] = 
    (((consume[Unit, SEvent, ({ type λ[α] = IdT[IO, α] })#λ, Set] &= eval(graph).enum[IdT]) run { err => sys.error("O NOES!!!") }) run) unsafePerformIO
}
