package org.alephium.flow.storage

import akka.actor.{ActorRef, Props}
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.{PeerManager, TcpHandler}
import org.alephium.protocol.message.{SendBlocks, SendHeaders}
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.{AVector, BaseActor}

object BlockChainHandler {
  def props(blockFlow: BlockFlow, chainIndex: ChainIndex, peerManager: ActorRef)(
      implicit config: PlatformConfig): Props =
    Props(new BlockChainHandler(blockFlow, chainIndex, peerManager))

  sealed trait Command
  case class AddBlocks(blocks: AVector[Block], origin: DataOrigin) extends Command
}

// TODO: investigate concurrency in master branch
class BlockChainHandler(val blockFlow: BlockFlow, val chainIndex: ChainIndex, peerManager: ActorRef)(
    implicit val config: PlatformConfig)
    extends BaseActor
    with ChainHandlerLogger {
  val chain: BlockPool = blockFlow.getBlockChain(chainIndex)

  override def receive: Receive = {
    case BlockChainHandler.AddBlocks(blocks, origin) =>
      // TODO: support more blocks later
      assert(blocks.length == 1)
      val block = blocks.head

      val result = blockFlow.add(block)
      result match {
        case AddBlockResult.Success =>
          logInfo(block.header)
          broadcast(block, origin)
        case AddBlockResult.AlreadyExisted =>
          log.debug(s"Block already existed")
        case x: AddBlockResult.Incomplete =>
          // TODO: handle missing data
          log.debug(s"No enough data to verify block: ${x.toString}")
        case x: AddBlockResult.Error =>
          log.warning(s"Failed in adding new block: ${x.toString}")
      }

      sender() ! result
  }

  def broadcast(block: Block, origin: DataOrigin): Unit = {
    val blockMessage  = TcpHandler.envelope(SendBlocks(AVector(block)))
    val headerMessage = TcpHandler.envelope(SendHeaders(AVector(block.header)))
    peerManager ! PeerManager.BroadCastBlock(block, blockMessage, headerMessage, origin)
  }
}
