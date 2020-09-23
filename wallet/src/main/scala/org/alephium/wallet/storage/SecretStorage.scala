package org.alephium.wallet.storage

import java.io.{File, PrintWriter}
import java.nio.file.{Files, Path}

import scala.io.Source
import scala.util.{Try, Using}

import akka.util.ByteString
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import io.circe.parser.decode
import io.circe.syntax._

import org.alephium.crypto.{AES, Sha256}
import org.alephium.crypto.wallet.BIP32
import org.alephium.crypto.wallet.BIP32.ExtendedPrivateKey
import org.alephium.serde.{deserialize, serialize, Serde}
import org.alephium.util.AVector
import org.alephium.wallet.Constants
import org.alephium.wallet.circe.UtilCodecs

trait SecretStorage {
  def lock(): Unit
  def unlock(password: String): Either[SecretStorage.Error, Unit]
  def getCurrentPrivateKey(): Either[SecretStorage.Error, ExtendedPrivateKey]
  def getAllPrivateKeys()
    : Either[SecretStorage.Error, (ExtendedPrivateKey, AVector[ExtendedPrivateKey])]
  def deriveNextKey(): Either[SecretStorage.Error, ExtendedPrivateKey]
  def changeActiveKey(key: ExtendedPrivateKey): Either[SecretStorage.Error, Unit]
}

object SecretStorage extends UtilCodecs {

  sealed trait Error

  case object Locked                  extends Error
  case object CannotDeriveKey         extends Error
  case object CannotParseFile         extends Error
  case object CannotDecryptSecret     extends Error
  case object InvalidState            extends Error
  case object SecretDirError          extends Error
  case object SecretFileError         extends Error
  case object SecretFileAlreadyExists extends Error
  case object UnknownKey              extends Error

  final case class StoredState(seed: ByteString, numberOfAddresses: Int, activeAddressIndex: Int)

  object StoredState {
    implicit val serde: Serde[StoredState] =
      Serde.forProduct3(apply,
                        state => (state.seed, state.numberOfAddresses, state.activeAddressIndex))
  }

  private final case class State(seed: ByteString,
                                 password: String,
                                 activeKey: ExtendedPrivateKey,
                                 privateKeys: AVector[ExtendedPrivateKey])

  implicit val codec: Codec[AES.Encrypted] = deriveCodec[AES.Encrypted]

  def load(seed: ByteString, password: String, secretDir: Path): Either[Error, SecretStorage] = {
    withFileOrCreate(seed, password, secretDir)(file => fromFile(file, password))
  }

  def create(seed: ByteString, password: String, secretDir: Path): Either[Error, SecretStorage] = {
    withFileOrCreate(seed, password, secretDir)(_ => Left(SecretFileAlreadyExists))
  }

  private def withFileOrCreate(seed: ByteString, password: String, secretDir: Path)(
      onFileExist: File => Either[Error, SecretStorage]): Either[Error, SecretStorage] = {
    val name = Sha256.hash(Sha256.hash(seed)).shortHex
    val file = new File(s"$secretDir/$name.json")
    if (file.exists) {
      onFileExist(file)
    } else {
      for {
        _ <- Try(Files.createDirectories(secretDir)).toEither.left.map(_ => SecretDirError)
        _ <- storeStateToFile(file,
                              StoredState(seed, numberOfAddresses = 1, activeAddressIndex = 0),
                              password)
      } yield {
        new Impl(file)
      }
    }
  }

  private[storage] def fromFile(file: File, password: String): Either[Error, SecretStorage] = {
    for {
      _ <- stateFromFile(file, password)
    } yield {
      new Impl(file)
    }
  }

  //TODO add some `synchronized` for the state
  private class Impl(file: File) extends SecretStorage {

    private var maybeState: Option[State] = None

    override def lock(): Unit = {
      maybeState = None
    }

    override def unlock(password: String): Either[Error, Unit] = {
      for {
        state <- stateFromFile(file, password)
      } yield {
        maybeState = Some(state)
      }
    }

    override def getCurrentPrivateKey(): Either[Error, ExtendedPrivateKey] = {
      for {
        state <- getState
      } yield {
        state.activeKey
      }
    }

    def getAllPrivateKeys(): Either[Error, (ExtendedPrivateKey, AVector[ExtendedPrivateKey])] = {
      for {
        state <- getState
      } yield {
        (state.activeKey, state.privateKeys)
      }
    }
    override def deriveNextKey(): Either[Error, ExtendedPrivateKey] = {
      for {
        state         <- getState
        latestKey     <- state.privateKeys.lastOption.toRight(InvalidState)
        newPrivateKey <- deriveNextPrivateKey(state.seed, latestKey)
        keys = state.privateKeys :+ newPrivateKey
        _ <- storeStateToFile(file,
                              StoredState(state.seed, keys.length, keys.length - 1),
                              state.password)
      } yield {
        maybeState = Some(State(state.seed, state.password, newPrivateKey, keys))
        newPrivateKey
      }
    }

    override def changeActiveKey(key: ExtendedPrivateKey): Either[SecretStorage.Error, Unit] = {
      for {
        state <- getState
        index = state.privateKeys.indexWhere(_.privateKey == key.privateKey)
        _ <- Either.cond(index >= 0, (), UnknownKey)
        _ <- storeStateToFile(file,
                              StoredState(state.seed, state.privateKeys.length, index),
                              state.password)
      } yield {
        maybeState = Some(state.copy(activeKey = key))
        ()
      }
    }

    private def getState: Either[Error, State] = maybeState.toRight(Locked)
  }

  private def stateFromFile(file: File, password: String): Either[Error, State] = {
    Using(Source.fromFile(file)) { source =>
      val rawFile = source.getLines().mkString
      for {
        encrypted   <- decode[AES.Encrypted](rawFile).left.map(_ => CannotParseFile)
        stateBytes  <- AES.decrypt(encrypted, password).toEither.left.map(_ => CannotDecryptSecret)
        state       <- deserialize[StoredState](stateBytes).left.map(_ => SecretFileError)
        privateKeys <- deriveKeys(state.seed, state.numberOfAddresses)
        active      <- privateKeys.get(state.activeAddressIndex).toRight(InvalidState)
      } yield {
        State(state.seed, password, active, privateKeys)
      }
    }.toEither.left.map(_ => SecretFileError).flatten
  }

  private def deriveKeys(seed: ByteString,
                         number: Int): Either[Error, AVector[ExtendedPrivateKey]] = {
    if (number <= 0) {
      Right(AVector.empty)
    } else {
      BIP32
        .btcMasterKey(seed)
        .derive(Constants.path.toSeq)
        .toRight(CannotDeriveKey)
        .flatMap { firstKey =>
          if (number <= 1) {
            Right(AVector(firstKey))
          } else {
            AVector
              .from((2 to number))
              .foldE((firstKey, AVector(firstKey))) {
                case ((prevKey, keys), _) =>
                  deriveNextPrivateKey(seed, prevKey).map { newKey =>
                    (newKey, keys :+ newKey)
                  }
              }
              .map { case (_, keys) => keys }
          }
        }
    }
  }

  private def deriveNextPrivateKey(
      seed: ByteString,
      privateKey: ExtendedPrivateKey): Either[Error, ExtendedPrivateKey] =
    (for {
      index  <- privateKey.path.lastOption.map(_ + 1)
      parent <- BIP32.btcMasterKey(seed).derive(Constants.path.dropRight(1).toSeq)
      child  <- parent.derive(index)
    } yield child).toRight(CannotDeriveKey)

  private def storeStateToFile(file: File,
                               storedState: StoredState,
                               password: String): Either[Error, Unit] = {
    Using
      .Manager { use =>
        val outWriter = use(new PrintWriter(file))
        val encrypted = AES.encrypt(serialize(storedState), password)
        outWriter.write(encryptedAsJson(encrypted))
      }
      .toEither
      .left
      .map(_ => SecretFileError)
  }

  private def encryptedAsJson(encrypted: AES.Encrypted) = {
    // scalastyle:off regex
    encrypted.asJson.noSpaces
    // scalastyle:on
  }
}
