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
package com.precog
package daze

import org.specs2.execute.Result
import org.specs2.mutable.Specification

import com.precog.bytecode.JType.JUnfixedT
import com.precog.yggdrasil.SObject
import com.precog.yggdrasil.test.YId
import com.precog.util.IdGen

import scalaz.Failure
import scalaz.Success

trait JoinOptimizerSpecs[M[+_]] extends Specification
    with Evaluator[M]
    with JoinOptimizer
    with StdLib[M]
    with TestConfigComponent[M] 
    with MemoryDatasetConsumer[M] { self =>

  import Function._
  
  import dag._
  import instructions.{ DerefObject, Eq, JoinObject, Line, PushString, WrapObject }

  val testUID = "testUID"

  def testEval(graph: DepGraph)(test: Set[SEvent] => Result): Result = withContext { ctx =>
    (consumeEval(testUID, graph, ctx) match {
      case Success(results) => test(results)
      case Failure(error) => throw error
    }) 
  }

  "optimizer" should {
    "eliminate naive cartesian products in trivial cases" in {
      
      val rawInput = """
        | a := //users
        | b := //heightWeight
        | a ~ b
        |   { name: a.name, height: b.height } where a.userId = b.userId """.stripMargin
        
      val line = Line(0, "")
      val users = LoadLocal(line,Root(line,PushString("/hom/users")), JUnfixedT)
      val heightWeight = LoadLocal(line,Root(line,PushString("/hom/heightWeight")), JUnfixedT)
      val height = Root(line, PushString("height"))
      val name = Root(line, PushString("name"))
      val userId = Root(line, PushString("userId"))

      val input =
        Filter(line, IdentitySort,
          Join(line, JoinObject, CrossLeftSort,
            Join(line, WrapObject, CrossLeftSort,
              height,
              Join(line, DerefObject, CrossLeftSort, heightWeight, height)),
            Join(line, WrapObject, CrossLeftSort,
              name,
              Join(line, DerefObject, CrossLeftSort, users, name))),
          Join(line, Eq, CrossLeftSort,
            Join(line, DerefObject, CrossLeftSort,
              users,
              userId),
            Join(line, DerefObject, CrossLeftSort,
              heightWeight,
              userId)))    
      
      val opt = optimize(input, new IdGen)
              
      val expectedOpt =
        Join(line, JoinObject, ValueSort(0),
          Join(line, WrapObject, CrossLeftSort,
            height,
            SortBy(heightWeight, "userId", "height", 0)), 
          Join(line, WrapObject, CrossLeftSort,
            name,
            SortBy(users, "userId", "name", 0))) 

      opt must_== expectedOpt
    }

    "eliminate naive cartesian products in slightly less trivial cases (1)" in {
      
      val rawInput = """
        | a := //users
        | b := //heightWeight
        | a ~ b
        |   { name: a.name, height: b.height, weight: b.weight } where a.userId = b.userId """.stripMargin
        
      val line = Line(0, "")
      lazy val users = LoadLocal(line, Root(line, PushString("/users")))
      lazy val heightWeight = LoadLocal(line, Root(line, PushString("/heightWeight")))
      lazy val userId = Root(line, PushString("userId"))
      lazy val name = Root(line, PushString("name"))
      lazy val height = Root(line, PushString("height"))
      lazy val weight = Root(line, PushString("weight"))
      
      lazy val input =
        Filter(line, IdentitySort,
          Join(line, JoinObject, CrossLeftSort,
            Join(line, JoinObject, IdentitySort,
              Join(line, WrapObject, CrossLeftSort,
                height,
                Join(line, DerefObject, CrossLeftSort, heightWeight, height)
              ),
              Join(line, WrapObject, CrossLeftSort,
                weight,
                Join(line, DerefObject, CrossLeftSort, heightWeight, weight)
              )
            ),
            Join(line, WrapObject, CrossLeftSort,
              name,
              Join(line, DerefObject, CrossLeftSort, users, name)
            )
          ),
          Join(line, Eq, CrossLeftSort,
            Join(line, DerefObject, CrossLeftSort, users, userId),
            Join(line, DerefObject, CrossLeftSort, heightWeight, userId)
          )
        )

      val opt = optimize(input, new IdGen)
              
      val expectedOpt =
        Join(line, JoinObject, ValueSort(0),
          Join(line, JoinObject, IdentitySort,
            Join(line, WrapObject, CrossLeftSort,
              height,
              SortBy(heightWeight, "userId", "height", 0)),
            Join(line, WrapObject, CrossLeftSort,
              weight,
              SortBy(heightWeight, "userId", "weight", 0))), 
          Join(line, WrapObject, CrossLeftSort,
            name,
            SortBy(users, "userId", "name", 0))) 

       opt must_== expectedOpt
    }

    "eliminate naive cartesian products in slightly less trivial cases (2)" in {
      
      val rawInput = """
        | a := //users
        | b := //heightWeight
        | a ~ b
        |   ({ name: a.name } with b) where a.userId = b.userId """.stripMargin

      val line = Line(0, "")
      
      lazy val users = LoadLocal(line, Root(line, PushString("/users")))
      lazy val heightWeight = LoadLocal(line, Root(line, PushString("/heightWeight")))
      lazy val userId = Root(line, PushString("userId"))
      lazy val name = Root(line, PushString("name"))
      lazy val key = Root(line, PushString("key"))
      lazy val value = Root(line, PushString("value"))
      
      lazy val input =
        Filter(line, IdentitySort,
          Join(line, JoinObject, CrossLeftSort,
            Join(line, WrapObject, CrossLeftSort,
              name,
              Join(line, DerefObject, CrossLeftSort, users, name)
            ),
            heightWeight
          ),
          Join(line, Eq, CrossLeftSort,
            Join(line, DerefObject, CrossLeftSort, users, userId),
            Join(line, DerefObject, CrossLeftSort, heightWeight, userId)
          )
        )

      val opt = optimize(input, new IdGen)

      val expectedOpt =
        Join(line, JoinObject, ValueSort(0),
          SortBy(
            Join(line, JoinObject, IdentitySort,
              Join(line, WrapObject, CrossLeftSort,
                key,
                Join(line, DerefObject, CrossLeftSort, heightWeight, userId)),
              Join(line, WrapObject, CrossLeftSort, value, heightWeight)),
            "key", "value", 0), 
          Join(line, WrapObject, CrossLeftSort,
            name,
            SortBy(users, "userId", "name", 0)))

      opt must_== expectedOpt
    }.pendingUntilFixed
  }
}

object JoinOptimizerSpecs extends JoinOptimizerSpecs[YId] {
  val M = YId.M
  val coM = YId.M
}
