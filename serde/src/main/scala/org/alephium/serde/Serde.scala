package org.alephium.serde

import scala.annotation.tailrec
import scala.collection.mutable
import scala.reflect.ClassTag

import akka.util.ByteString

import org.alephium.util.{AVector, Bytes}

trait Serde[T] extends Serializer[T] with Deserializer[T] { self =>
  // Note: make sure that T and S are isomorphic
  def xmap[S](to: T => S, from: S => T): Serde[S] = new Serde[S] {
    override def serialize(input: S): ByteString = {
      self.serialize(from(input))
    }

    override def _deserialize(input: ByteString): SerdeResult[(S, ByteString)] = {
      self._deserialize(input).map {
        case (t, rest) => (to(t), rest)
      }
    }

    override def deserialize(input: ByteString): SerdeResult[S] = {
      self.deserialize(input).map(to)
    }
  }

  def xfmap[S](to: T => SerdeResult[S], from: S => T): Serde[S] = new Serde[S] {
    override def serialize(input: S): ByteString = {
      self.serialize(from(input))
    }

    override def _deserialize(input: ByteString): SerdeResult[(S, ByteString)] = {
      self._deserialize(input).flatMap {
        case (t, rest) => to(t).map((_, rest))
      }
    }

    override def deserialize(input: ByteString): SerdeResult[S] = {
      self.deserialize(input).flatMap(to)
    }
  }

  def xomap[S](to: T => Option[S], from: S => T): Serde[S] =
    xfmap(to(_) match {
      case Some(s) => Right(s)
      case None    => Left(SerdeError.validation("validation error"))
    }, from)

  def validate(test: T => Either[String, Unit]): Serde[T] = new Serde[T] {
    override def serialize(input: T): ByteString = self.serialize(input)

    override def _deserialize(input: ByteString): SerdeResult[(T, ByteString)] = {
      self._deserialize(input).flatMap {
        case (t, rest) =>
          test(t) match {
            case Right(_)    => Right((t, rest))
            case Left(error) => Left(SerdeError.validation(error))
          }
      }
    }

    override def deserialize(input: ByteString): SerdeResult[T] = {
      self.deserialize(input).flatMap { t =>
        test(t) match {
          case Right(_)    => Right(t)
          case Left(error) => Left(SerdeError.validation(error))
        }
      }
    }
  }
}

trait FixedSizeSerde[T] extends Serde[T] {
  def serdeSize: Int

  def deserialize0(input: ByteString, f: ByteString => T): SerdeResult[T] =
    if (input.size == serdeSize) {
      Right(f(input))
    } else if (input.size > serdeSize) {
      Left(SerdeError.redundant(serdeSize, input.size))
    } else {
      Left(SerdeError.notEnoughBytes(serdeSize, input.size))
    }

  override def _deserialize(input: ByteString): SerdeResult[(T, ByteString)] =
    if (input.size >= serdeSize) {
      val (init, rest) = input.splitAt(serdeSize)
      deserialize(init).map((_, rest))
    } else Left(SerdeError.notEnoughBytes(serdeSize, input.size))
}

object Serde extends ProductSerde {
  private[serde] object BoolSerde extends FixedSizeSerde[Boolean] {
    override val serdeSize: Int = java.lang.Byte.BYTES

    override def serialize(input: Boolean): ByteString = {
      ByteString(if (input) 1 else 0)
    }

    override def deserialize(input: ByteString): SerdeResult[Boolean] =
      ByteSerde.deserialize(input).flatMap {
        case 0    => Right(false)
        case 1    => Right(true)
        case byte => Left(SerdeError.validation(s"Invalid bool from byte $byte"))
      }
  }

  private[serde] object ByteSerde extends FixedSizeSerde[Byte] {
    override val serdeSize: Int = java.lang.Byte.BYTES

    override def serialize(input: Byte): ByteString = {
      ByteString(input)
    }

    override def deserialize(input: ByteString): SerdeResult[Byte] =
      deserialize0(input, _.apply(0))
  }

  private[serde] object IntSerde extends FixedSizeSerde[Int] {
    override val serdeSize: Int = java.lang.Integer.BYTES

    override def serialize(input: Int): ByteString = Bytes.toBytes(input)

    override def deserialize(input: ByteString): SerdeResult[Int] =
      deserialize0(input, Bytes.toIntUnsafe)
  }

  private[serde] object LongSerde extends FixedSizeSerde[Long] {
    override val serdeSize: Int = java.lang.Long.BYTES

    override def serialize(input: Long): ByteString = Bytes.toBytes(input)

    override def deserialize(input: ByteString): SerdeResult[Long] =
      deserialize0(input, Bytes.toLongUnsafe)
  }

  private[serde] object ByteStringSerde extends Serde[ByteString] {
    override def serialize(input: ByteString): ByteString = {
      IntSerde.serialize(input.size) ++ input
    }

    override def _deserialize(input: ByteString): SerdeResult[(ByteString, ByteString)] = {
      IntSerde._deserialize(input).flatMap {
        case (size, rest) =>
          if (rest.size >= size) {
            Right(rest.splitAt(size))
          } else {
            Left(SerdeError.notEnoughBytes(size, rest.size))
          }
      }
    }
  }

  private object Flags {
    val none: Int  = 0
    val some: Int  = 1
    val left: Int  = 0
    val right: Int = 1

    val noneB: Byte  = none.toByte
    val someB: Byte  = some.toByte
    val leftB: Byte  = left.toByte
    val rightB: Byte = right.toByte
  }

  private[serde] class OptionSerde[T](serde: Serde[T]) extends Serde[Option[T]] {
    override def serialize(input: Option[T]): ByteString = input match {
      case None    => ByteSerde.serialize(Flags.noneB)
      case Some(t) => ByteSerde.serialize(Flags.someB) ++ serde.serialize(t)
    }

    override def _deserialize(input: ByteString): SerdeResult[(Option[T], ByteString)] = {
      ByteSerde._deserialize(input).flatMap {
        case (flag, rest) =>
          if (flag == Flags.none) {
            Right((None, rest))
          } else if (flag == Flags.some) {
            serde._deserialize(rest).map { case (t, r) => (Some(t), r) }
          } else {
            Left(SerdeError.wrongFormat(s"expect 0 or 1 for option flag"))
          }
      }
    }
  }

  private[serde] class EitherSerde[A, B](serdeA: Serde[A], serdeB: Serde[B])
      extends Serde[Either[A, B]] {
    override def serialize(input: Either[A, B]): ByteString = input match {
      case Left(a)  => ByteSerde.serialize(Flags.leftB) ++ serdeA.serialize(a)
      case Right(b) => ByteSerde.serialize(Flags.rightB) ++ serdeB.serialize(b)
    }

    override def _deserialize(input: ByteString): SerdeResult[(Either[A, B], ByteString)] = {
      ByteSerde._deserialize(input).flatMap {
        case (flag, rest) =>
          if (flag == Flags.left) {
            serdeA._deserialize(rest).map { case (a, r) => (Left(a), r) }
          } else if (flag == Flags.right) {
            serdeB._deserialize(rest).map { case (b, r) => (Right(b), r) }
          } else {
            Left(SerdeError.wrongFormat(s"expect 0 or 1 for either flag"))
          }
      }
    }
  }

  private[serde] class BatchDeserializer[T: ClassTag](deserializer: Deserializer[T]) {
    @tailrec
    private def __deserializeSeq[C <: mutable.IndexedSeq[T]](
        rest: ByteString,
        index: Int,
        length: Int,
        builder: mutable.Builder[T, C]): SerdeResult[(C, ByteString)] = {
      if (index == length) Right(builder.result() -> rest)
      else {
        deserializer._deserialize(rest) match {
          case Right((t, tRest)) =>
            builder += t
            __deserializeSeq(tRest, index + 1, length, builder)
          case Left(e) => Left(e)
        }
      }
    }

    final def _deserializeSeq[C <: mutable.IndexedSeq[T]](
        size: Int,
        input: ByteString,
        newBuilder: => mutable.Builder[T, C]): SerdeResult[(C, ByteString)] = {
      val builder = newBuilder
      builder.sizeHint(size)
      __deserializeSeq(input, 0, size, builder)
    }

    @tailrec
    private def _deserializeArray(rest: ByteString,
                                  index: Int,
                                  output: Array[T]): SerdeResult[(Array[T], ByteString)] = {
      if (index == output.length) Right(output -> rest)
      else {
        deserializer._deserialize(rest) match {
          case Right((t, tRest)) =>
            output.update(index, t)
            _deserializeArray(tRest, index + 1, output)
          case Left(e) => Left(e)
        }
      }
    }

    def _deserializeArray(n: Int, input: ByteString): SerdeResult[(Array[T], ByteString)] = {
      _deserializeArray(input, 0, Array.ofDim[T](n))
    }

    def _deserializeAVector(n: Int, input: ByteString): SerdeResult[(AVector[T], ByteString)] = {
      _deserializeArray(n, input).map(t => AVector.unsafe(t._1) -> t._2)
    }
  }

  private[serde] def bytesSerde(bytes: Int): Serde[ByteString] = new FixedSizeSerde[ByteString] {
    override val serdeSize: Int = bytes

    override def serialize(bs: ByteString): ByteString = {
      assume(bs.length == serdeSize)
      bs
    }

    override def deserialize(input: ByteString): SerdeResult[ByteString] =
      deserialize0(input, identity)
  }

  private[serde] def fixedSizeSerde[T: ClassTag](size: Int, serde: Serde[T]): Serde[AVector[T]] =
    new BatchDeserializer[T](serde) with Serde[AVector[T]] {
      override def serialize(input: AVector[T]): ByteString = {
        input.map(serde.serialize).fold(ByteString.empty)(_ ++ _)
      }

      override def _deserialize(input: ByteString): SerdeResult[(AVector[T], ByteString)] = {
        _deserializeAVector(size, input)
      }
    }

  private[serde] class AVectorSerializer[T: ClassTag](serializer: Serializer[T])
      extends Serializer[AVector[T]] {
    override def serialize(input: AVector[T]): ByteString = {
      input.map(serializer.serialize).fold(IntSerde.serialize(input.length))(_ ++ _)
    }
  }

  private[serde] class AVectorDeserializer[T: ClassTag](deserializer: Deserializer[T])
      extends BatchDeserializer[T](deserializer)
      with Deserializer[AVector[T]] {
    override def _deserialize(input: ByteString): SerdeResult[(AVector[T], ByteString)] = {
      IntSerde._deserialize(input).flatMap {
        case (size, rest) => _deserializeAVector(size, rest)
      }
    }
  }

  private[serde] def avectorSerde[T: ClassTag](serde: Serde[T]): Serde[AVector[T]] =
    new BatchDeserializer[T](serde) with Serde[AVector[T]] {
      override def serialize(input: AVector[T]): ByteString = {
        input.map(serde.serialize).fold(IntSerde.serialize(input.length))(_ ++ _)
      }

      override def _deserialize(input: ByteString): SerdeResult[(AVector[T], ByteString)] = {
        IntSerde._deserialize(input).flatMap {
          case (size, rest) => _deserializeAVector(size, rest)
        }
      }
    }

  private[serde] def dynamicSizeSerde[C <: mutable.IndexedSeq[T], T: ClassTag](
      serde: Serde[T],
      newBuilder: => mutable.Builder[T, C]): Serde[C] =
    new BatchDeserializer[T](serde) with Serde[C] {
      override def serialize(input: C): ByteString = {
        input.map(serde.serialize).fold(IntSerde.serialize(input.length))(_ ++ _)
      }

      override def _deserialize(input: ByteString): SerdeResult[(C, ByteString)] = {
        IntSerde._deserialize(input).flatMap {
          case (size, rest) => _deserializeSeq(size, rest, newBuilder)
        }
      }
    }
}
