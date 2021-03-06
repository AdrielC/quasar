/*
 * Copyright 2020 Precog Data
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

package quasar.connector

import scala.{Byte, Unit}

import java.net.URI

import fs2.Pipe

package object destination {
  /** A service to support pull-based Destinations.
    *
    * Given a function that reads data from a URL, provides a sink that
    * will initiate the pull, making the input bytes available at the URL
    * provided to the given function.
    *
    * The effect returned from the read function should semantically block
    * until the destination has fully ingested the bytes read, if possible.
    */
  type PushmiPullyu[F[_]] = (URI => F[Unit]) => Pipe[F, Byte, Unit]
}
