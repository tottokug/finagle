package com.twitter.finagle.transport

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.{
  CancelledWriteException, ChannelClosedException, ChannelException}
import com.twitter.util.{Future, Return, Promise, Time}
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import org.jboss.netty.channel._

class ChannelTransport[In, Out](ch: Channel)
  extends Transport[In, Out] with ChannelUpstreamHandler
{
  private[this] var nneed = 0
  private[this] def need(n: Int) = synchronized {
    nneed += n
    // Note: we buffer 1 message here so that we receive socket
    // closes proactively.
    val r = nneed >= 0
    if (ch.isReadable != r && ch.isOpen)
      ch.setReadable(r)
  }

  ch.getPipeline.addLast("finagleTransportBridge", this)

  private[this] val readq = new AsyncQueue[Out]
  private[this] val failed = new AtomicBoolean(false)

  private[this] def fail(exc: Throwable) {
    if (!failed.compareAndSet(false, true))
      return

    close()
    closep.updateIfEmpty(Return(exc))
    readq.fail(exc)
  }

  override def handleUpstream(ctx: ChannelHandlerContext, e: ChannelEvent) {
    e match {
      case msg: MessageEvent =>
        readq.offer(msg.getMessage.asInstanceOf[Out])
        need(-1)

      case e: ChannelStateEvent
      if e.getState == ChannelState.OPEN && e.getValue != java.lang.Boolean.TRUE =>
        fail(new ChannelClosedException(ch.getRemoteAddress))

      case e: ChannelStateEvent
      if e.getState == ChannelState.INTEREST_OPS =>
        // Make sure we have the right interest ops. This allows us to fix
        // up any races that may occur when setting interest ops without
        // having to explicitly serialize them -- it guarantees convergence
        // of interest ops.
        //
        // This can't deadlock, because:
        //    #1 Updates from other threads are enqueued onto a pending 
        //    operations queue for the owner thread, and they never wait
        //    for completion.
        //    #2 Within the context of this thread, Channel.isReadable cannot
        //    change while we're invoking setReadable(): subsequent channel
        //    state events will be terminated early by need()'s check.
        need(0)
        
      case e: ChannelStateEvent
      if e.getState == ChannelState.CONNECTED
          && e.getValue == java.lang.Boolean.TRUE =>
        need(0)

      case e: ExceptionEvent =>
        fail(ChannelException(e.getCause, ch.getRemoteAddress))

      case _ =>  // drop.
    }

    // We terminate the upstream here on purpose: this must always
    // be the last handler.
  }

  def write(msg: In): Future[Unit] = {
    val p = new Promise[Unit]

    Channels.write(ch, msg).addListener(new ChannelFutureListener {
      def operationComplete(f: ChannelFuture) {
        if (f.isSuccess)
          p.setDone()
        else if (f.isCancelled)
          p.setException(new CancelledWriteException)
        else
          p.setException(ChannelException(f.getCause, ch.getRemoteAddress))
      }
    })

    p
  }

  def read(): Future[Out] = {
    need(1)
    readq.poll()
  }

  def isOpen = ch.isOpen

  def close(deadline: Time) = {
    if (ch.isOpen)
      Channels.close(ch)
    closep map { _ => () }
  }

  def localAddress: SocketAddress = ch.getLocalAddress()
  def remoteAddress: SocketAddress = ch.getRemoteAddress()

  private[this] val closep = new Promise[Throwable]
  val onClose: Future[Throwable] = closep

  override def toString = "Transport<%s>".format(ch)
}
