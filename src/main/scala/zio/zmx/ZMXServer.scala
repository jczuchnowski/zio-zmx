/*
 * Copyright 2017-2019 John A. De Goes and the ZIO Contributors
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

package zio.zmx

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{ SelectionKey, Selector, ServerSocketChannel, SocketChannel }
import java.util.Iterator

import scala.collection.mutable.Set
import scala.jdk.CollectionConverters._
import zio.{ Fiber, IO, UIO, URIO, ZIO }

object ZMXServer {
  val BUFFER_SIZE = 256

  private def register(selector: Selector, serverSocket: ServerSocketChannel): SelectionKey = {
    val client: SocketChannel = serverSocket.accept()
    client.configureBlocking(false)
    client.register(selector, SelectionKey.OP_READ)
  }

  final val getCommand: PartialFunction[ZMXServerRequest, ZMXCommands] = {
    case ZMXServerRequest(command, None) if command.equalsIgnoreCase("dump") => ZMXCommands.FiberDump
    case ZMXServerRequest(command, None) if command.equalsIgnoreCase("test") => ZMXCommands.Test
    case ZMXServerRequest(command, None) if command.equalsIgnoreCase("stop") => ZMXCommands.Stop
  }

  private def handleCommand(command: ZMXCommands): UIO[ZMXMessage] =
    command match {
      case ZMXCommands.FiberDump =>
        for {
          dumps  <- Fiber.dumpAll
          result <- URIO.foreach(dumps)(_.prettyPrintM)
        } yield ZMXMessage(result.mkString("\n"))
      case ZMXCommands.Test => ZIO.succeed(ZMXMessage("This is a TEST"))
      case _                => ZIO.succeed(ZMXMessage("Unknown Command"))
    }

  private def processCommand(received: String): IO[Unit, ZMXCommands] = {
    val request: Option[ZMXServerRequest] = ZMXProtocol.serverReceived(received)
    ZIO.fromOption(request.map(getCommand(_)))
  }

  private def responseReceived(buffer: ByteBuffer, key: SelectionKey, debug: Boolean): ZIO[Any, Unit, ByteBuffer] = {
    val received: String = ZMXProtocol.ByteBufferToString(buffer)
    if (debug && !received.isEmpty)
      println(s"Server received: [*****\n${received}\n*****]")
    for {
      input   <- ZIO.fromOption(Option(received))
      command <- processCommand(input)
      message <- handleCommand(command)
      reply   <- ZMXProtocol.generateReply(message, Success)
    } yield {
      ZMXProtocol.writeToClient(buffer, key, reply)
    }
  }

  def apply(config: ZMXConfig): Unit = {
    val selector: Selector             = Selector.open()
    val zmxSocket: ServerSocketChannel = ServerSocketChannel.open()
    val zmxAddress: InetSocketAddress  = new InetSocketAddress(config.host, config.port)
    zmxSocket.socket.setReuseAddress(true)
    zmxSocket.bind(zmxAddress)
    zmxSocket.configureBlocking(false)
    zmxSocket.register(selector, SelectionKey.OP_ACCEPT)
    val buffer: ByteBuffer = ByteBuffer.allocate(BUFFER_SIZE)

    while (true) {
      selector.select()
      val zmxKeys: Set[SelectionKey]      = selector.selectedKeys.asScala
      val zmxIter: Iterator[SelectionKey] = zmxKeys.iterator.asJava
      while (zmxIter.hasNext) {
        val currentKey: SelectionKey = zmxIter.next
        if (currentKey.isAcceptable) {
          register(selector, zmxSocket)
        }
        if (currentKey.isReadable) {
          responseReceived(buffer, currentKey, config.debug)
        }
        zmxIter.remove()
      }
    }
  }
}
