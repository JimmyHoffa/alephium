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

package org.alephium.protocol.vm

import scala.collection.mutable
import scala.util.Try

import org.alephium.protocol.model.{TokenId, TxOutput}
import org.alephium.util.{AVector, U256}

final case class BalancesPerLockup(
    var alphAmount: U256,
    tokenAmounts: mutable.Map[TokenId, U256],
    scopeDepth: Int
) {
  def tokenVector: AVector[(TokenId, U256)] = {
    import org.alephium.protocol.model.tokenIdOrder
    AVector.from(tokenAmounts.filter(_._2.nonZero)).sortBy(_._1)
  }

  def getTokenAmount(tokenId: TokenId): Option[U256] = tokenAmounts.get(tokenId)

  def addAlph(amount: U256): Option[Unit] = {
    alphAmount.add(amount).map(alphAmount = _)
  }

  def addToken(tokenId: TokenId, amount: U256): Option[Unit] = {
    tokenAmounts.get(tokenId) match {
      case Some(currentAmount) =>
        currentAmount.add(amount).map(tokenAmounts(tokenId) = _)
      case None =>
        tokenAmounts(tokenId) = amount
        Some(())
    }
  }

  def subAlph(amount: U256): Option[Unit] = {
    alphAmount.sub(amount).map(alphAmount = _)
  }

  def subToken(tokenId: TokenId, amount: U256): Option[Unit] = {
    tokenAmounts.get(tokenId).flatMap { currentAmount =>
      currentAmount.sub(amount).map(tokenAmounts(tokenId) = _)
    }
  }

  def add(another: BalancesPerLockup): Option[Unit] =
    Try {
      alphAmount = alphAmount.add(another.alphAmount).getOrElse(throw BalancesPerLockup.error)
      another.tokenAmounts.foreach { case (tokenId, amount) =>
        tokenAmounts.get(tokenId) match {
          case Some(currentAmount) =>
            tokenAmounts(tokenId) =
              currentAmount.add(amount).getOrElse(throw BalancesPerLockup.error)
          case None =>
            tokenAmounts(tokenId) = amount
        }
      }
    }.toOption

  def sub(another: BalancesPerLockup): Option[Unit] =
    Try {
      alphAmount = alphAmount.sub(another.alphAmount).getOrElse(throw BalancesPerLockup.error)
      another.tokenAmounts.foreach { case (tokenId, amount) =>
        tokenAmounts.get(tokenId) match {
          case Some(currentAmount) =>
            tokenAmounts(tokenId) =
              currentAmount.sub(amount).getOrElse(throw BalancesPerLockup.error)
          case None => throw BalancesPerLockup.error
        }
      }
    }.toOption

  def toTxOutput(lockupScript: LockupScript): ExeResult[Option[TxOutput]] = {
    val tokens = tokenVector
    if (alphAmount.isZero) {
      if (tokens.isEmpty) Right(None) else failed(InvalidOutputBalances)
    } else {
      Right(Some(TxOutput.from(alphAmount, tokens, lockupScript)))
    }
  }
}

object BalancesPerLockup {
  val error: ArithmeticException = new ArithmeticException("Balance amount")

  // Need to be `def` as it's mutable
  def empty: BalancesPerLockup = BalancesPerLockup(U256.Zero, mutable.Map.empty, 0)

  def alph(amount: U256): BalancesPerLockup = {
    BalancesPerLockup(amount, mutable.Map.empty, 0)
  }

  def token(id: TokenId, amount: U256): BalancesPerLockup = {
    BalancesPerLockup(U256.Zero, mutable.Map(id -> amount), 0)
  }

  def from(output: TxOutput): BalancesPerLockup =
    BalancesPerLockup(output.amount, mutable.Map.from(output.tokens.toIterable), 0)
}
