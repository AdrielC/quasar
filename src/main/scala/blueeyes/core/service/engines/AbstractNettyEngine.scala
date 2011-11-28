package blueeyes.core.service.engines

import blueeyes.core.service.{HttpServer, HttpServerEngine}
import blueeyes.concurrent.Future

trait AbstractNettyEngine extends HttpServerEngine with HttpServer{ self =>
  private val startStopLock = new java.util.concurrent.locks.ReentrantReadWriteLock
  private var servers: List[NettyServer]  = Nil

  override def start: Future[Unit] = {
    super.start.flatMapEither(_ => {
      startStopLock.writeLock.lock()
      try {
        servers = nettyServers
        servers.foreach(_.start)

        Right(())
      }
      catch {
        case e: Throwable => {
          Left(e)
        }
      }
      finally{
        startStopLock.writeLock.unlock()
      }
    })
  }

  override def stop: Future[Unit] = {
    super.stop.map(_ => {
      startStopLock.writeLock.lock()
      try {
        servers.foreach(_.stop)
        servers = Nil
      }
      finally{
        startStopLock.writeLock.unlock()
      }

      log.info("Netty engine is stopped.")
      ()
    })
  }

  protected def nettyServers: List[NettyServer]
}
