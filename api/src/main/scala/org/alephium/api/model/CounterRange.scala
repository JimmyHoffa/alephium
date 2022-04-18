// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.api.model

import sttp.tapir.{ValidationError, Validator}

final case class CounterRange(start: Int, endOpt: Option[Int])

object CounterRange {
  val validator: Validator[CounterRange] = Validator.custom { counterRange =>
    if (counterRange.start < 0) {
      List(ValidationError.Custom(counterRange, s"`start` must not be negative"))
    } else {
      if (counterRange.endOpt.exists(_ <= counterRange.start)) {
        List(ValidationError.Custom(counterRange, s"`end` must be larger than `start`"))
      } else {
        List.empty
      }
    }
  }
}
