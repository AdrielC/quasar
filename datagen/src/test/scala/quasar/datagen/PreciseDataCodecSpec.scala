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

package quasar.datagen

import quasar.{Data, DataArbitrary, DataCodec}
import quasar.ejson._

import fs2.{Pure, Stream}
import matryoshka.data.Fix
import scalaz.std.list._
import scalaz.std.string._

final class PreciseDataCodecSpec extends quasar.Qspec with DataArbitrary {
  type J = Fix[EJson]

  "precise data codec roundtrips" >> prop { d: Data =>
    val encoded =
      DataCodec.Precise.encode(d).map(_.nospaces)

    Stream.pure(encoded)
      .unNone
      .through(codec.ejsonDecodePreciseData[Pure, J])
      .through(codec.ejsonEncodePreciseData[Pure, J])
      .toList must equal(encoded.toList)
  }
}
