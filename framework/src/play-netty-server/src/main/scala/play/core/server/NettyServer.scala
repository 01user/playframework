/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.server

import com.typesafe.netty.http.pipelining.HttpPipeliningHandler
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.jboss.netty.bootstrap._
import org.jboss.netty.channel._
import org.jboss.netty.channel.Channels._
import org.jboss.netty.channel.group._
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.handler.logging.LoggingHandler
import org.jboss.netty.handler.ssl._
import org.jboss.netty.logging.InternalLogLevel
import play.api._
import play.core._
import play.core.server.netty._
import play.core.server.ssl.ServerSSLEngine
import play.server.SSLEngineProvider
import scala.util.control.NonFatal

/**
 * creates a Server implementation based Netty
 */
class NettyServer(config: ServerConfig, appProvider: ApplicationProvider) extends Server with ServerWithStop {

  private val nettyConfig = config.configuration.underlying.getConfig("play.server.netty")

  import NettyServer._

  def applicationProvider = appProvider
  def mode = config.mode

  private def newBootstrap: ServerBootstrap = {
    val serverBootstrap = new ServerBootstrap(
      new org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory(
        Executors.newCachedThreadPool(NamedThreadFactory("netty-boss")),
        Executors.newCachedThreadPool(NamedThreadFactory("netty-worker"))))

    import scala.collection.JavaConversions._
    // Find all properties that start with http.netty.option

    val options = nettyConfig.getConfig("option")

    object ExtractInt {
      def unapply(s: String) = try {
        Some(s.toInt)
      } catch {
        case e: NumberFormatException => None
      }
    }

    options.entrySet().foreach { entry =>
      val value = entry.getValue.unwrapped() match {
        case bool: java.lang.Boolean => bool
        case number: Number => number
        case null => null
        case "true" | "yes" => true
        case "false" | "no" => false
        case ExtractInt(number) => number
        case string: String => string
        case other => other.toString
      }

      val name = entry.getKey

      serverBootstrap.setOption(name, value)
    }

    serverBootstrap
  }

  class PlayPipelineFactory(secure: Boolean = false) extends ChannelPipelineFactory {

    private val logger = Logger(classOf[PlayPipelineFactory])

    def getPipeline = {
      val newPipeline = pipeline()
      if (secure) {
        sslEngineProvider.map { sslEngineProvider =>
          val sslEngine = sslEngineProvider.createSSLEngine()
          sslEngine.setUseClientMode(false)
          newPipeline.addLast("ssl", new SslHandler(sslEngine))
        }
      }
      val maxInitialLineLength = nettyConfig.getInt("maxInitialLineLength")
      val maxHeaderSize = nettyConfig.getInt("maxHeaderSize")
      val maxChunkSize = nettyConfig.getInt("maxChunkSize")
      newPipeline.addLast("decoder", new HttpRequestDecoder(maxInitialLineLength, maxHeaderSize, maxChunkSize))
      newPipeline.addLast("encoder", new HttpResponseEncoder())
      newPipeline.addLast("decompressor", new HttpContentDecompressor())
      val logWire = nettyConfig.getBoolean("log.wire")
      if (logWire) {
        newPipeline.addLast("logging", new LoggingHandler(InternalLogLevel.DEBUG))
      }
      newPipeline.addLast("http-pipelining", new HttpPipeliningHandler())
      newPipeline.addLast("handler", defaultUpStreamHandler)
      newPipeline
    }

    lazy val sslEngineProvider: Option[SSLEngineProvider] = //the sslContext should be reused on each connection
      try {
        Some(ServerSSLEngine.createSSLEngineProvider(config, applicationProvider))
      } catch {
        case NonFatal(e) =>
          logger.error(s"cannot load SSL context", e)
          None
      }

  }

  // Keep a reference on all opened channels (useful to close everything properly, especially in DEV mode)
  val allChannels = new DefaultChannelGroup

  // Our upStream handler is stateless. Let's use this instance for every new connection
  val defaultUpStreamHandler = new PlayDefaultUpstreamHandler(this, allChannels)

  // The HTTP server channel
  val HTTP = config.port.map { port =>
    val bootstrap = newBootstrap
    bootstrap.setPipelineFactory(new PlayPipelineFactory)
    val channel = bootstrap.bind(new InetSocketAddress(config.address, port))
    allChannels.add(channel)
    (bootstrap, channel)
  }

  // Maybe the HTTPS server channel
  val HTTPS = config.sslPort.map { port =>
    val bootstrap = newBootstrap
    bootstrap.setPipelineFactory(new PlayPipelineFactory(secure = true))
    val channel = bootstrap.bind(new InetSocketAddress(config.address, port))
    allChannels.add(channel)
    (bootstrap, channel)
  }

  mode match {
    case Mode.Test =>
    case _ =>
      HTTP.foreach { http =>
        logger.info("Listening for HTTP on %s".format(http._2.getLocalAddress))
      }
      HTTPS.foreach { https =>
        logger.info("Listening for HTTPS on port %s".format(https._2.getLocalAddress))
      }
  }

  override def stop() {

    appProvider.get.foreach(Play.stop)

    try {
      super.stop()
    } catch {
      case NonFatal(e) => logger.error("Error while stopping logger", e)
    }

    mode match {
      case Mode.Test =>
      case _ => logger.info("Stopping server...")
    }

    // First, close all opened sockets
    allChannels.close().awaitUninterruptibly()

    // Release the HTTP server
    HTTP.foreach(_._1.releaseExternalResources())

    // Release the HTTPS server if needed
    HTTPS.foreach(_._1.releaseExternalResources())

    mode match {
      case Mode.Dev =>
        Invoker.lazySystem.close()
        Execution.lazyContext.close()
      case _ => ()
    }
  }

  override lazy val mainAddress = {
    if (HTTP.isDefined) {
      HTTP.get._2.getLocalAddress.asInstanceOf[InetSocketAddress]
    } else {
      HTTPS.get._2.getLocalAddress.asInstanceOf[InetSocketAddress]
    }
  }

}

/**
 * Bootstraps Play application with a NettyServer backend.
 */
object NettyServer extends ServerStart {

  private val logger = Logger(this.getClass)

  val defaultServerProvider = new ServerProvider {
    def createServer(config: ServerConfig, appProvider: ApplicationProvider) = new NettyServer(config, appProvider)
  }

}
