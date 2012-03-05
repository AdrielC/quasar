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
package com.precog.performance

import org.specs2.mutable.Specification

import com.precog.yggdrasil.leveldb._

import org.iq80.leveldb._
import org.fusesource.leveldbjni.JniDBFactory._

import java.io.File
import java.nio.ByteBuffer

trait LeveldbPerformanceSpec extends Specification with PerformanceSpec {
  args(xonly = false)
  "leveldb" should {
    val tmpFile = File.createTempFile("insert_test", "_db")
  
    step {    
      tmpFile.delete
      tmpFile.mkdirs
    }

    "insert 1M elements in 4s".performBatch(1000000, 4000) { i =>
      val createOptions = (new Options).createIfMissing(true)  
      val db: DB = factory.open(tmpFile, createOptions)

      val key = new Array[Byte](8)
      val value = new Array[Byte](8)

      val keyBuf = ByteBuffer.wrap(key)
      val valueBuf = ByteBuffer.wrap(value)

      var cnt = 0

      while(cnt < i) {
        keyBuf.clear
        valueBuf.clear

        keyBuf.put(cnt)
        valueBuf.put(i-cnt)

        db.put(key, value)
        cnt += 1
      }

      db.close
    }

    "read 1M elements in 5s (naive)".performBatch(1000000, 5000) { i =>
      import org.fusesource.leveldbjni.internal.JniDBIterator
      val createOptions = (new Options).createIfMissing(true)  
      val db: DB = factory.open(tmpFile, createOptions)
      
      val chunkSize = 32 * 1024 
      
      val iter = db.iterator.asInstanceOf[JniDBIterator]
      iter.seekToFirst

      while(iter.hasNext) {
        val key = iter.peekNext.getKey
        val value = iter.peekNext.getValue
        iter.next
      }

      iter.close

      db.close
    }

    "read 1M elements in 750ms (batch)".performBatch(1000000, 750) { i =>
      import org.fusesource.leveldbjni.internal.JniDBIterator
      val createOptions = (new Options).createIfMissing(true)  
      val db: DB = factory.open(tmpFile, createOptions)
      
      val chunkSize = 32 * 1024 
      
      val iter = db.iterator.asInstanceOf[JniDBIterator]
      iter.seekToFirst

      while(iter.hasNext) {
        import org.fusesource.leveldbjni.KeyValueChunk
        val rawChunk: KeyValueChunk = iter.nextChunk(chunkSize)
        val actualChunkSize = rawChunk.getSize
        var el = 0
        while(el < actualChunkSize) {
          val key = rawChunk.keyAt(el)
          val value = rawChunk.valAt(el)
          el += 1
        }
      }

      iter.close

      db.close
    }
   
    // need cleanup here...
  }

}
