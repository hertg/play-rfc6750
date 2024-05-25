package org.example

import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.util.ByteString

import scala.collection.mutable

/**
 * This custom graph stage will buffer the first N bytes and pass them downstream as the first element,
 * if the incoming data exceeds the byteLimit, anything overflowing the buffer is sent downstream as the second element,
 * any data coming in after those two elements is passed downstream as is.
 *
 * If the incoming data does not exceed to byteLimit and the upstream stops sending data, the data
 * is sent downstream as one element of size < N.
 *
 * @param byteLimit Amount of bytes that should be buffered for the first message downstream
 */
class BufferFirstBytes(byteLimit: Int) extends GraphStage[FlowShape[ByteString, ByteString]] {

  private val in = Inlet[ByteString]("BufferFirstBytes.in")
  private val out = Outlet[ByteString]("BufferFirstBytes.out")

  override def shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new GraphStageLogic(shape) {
      var buffer: ByteString = ByteString.empty
      val queue: mutable.Queue[ByteString] = mutable.Queue()

      override def postStop(): Unit = {
        // wipe buffer and queue
        buffer = ByteString.empty
        queue.clear()
        super.postStop()
      }

      def continueHandler: InHandler with OutHandler = new InHandler with OutHandler {
        override def onPush(): Unit = {
          // directly pushing element downstream
          push(out, grab(in))
        }

        override def onPull(): Unit = {
          if (queue.nonEmpty) {
            // the queue contains elements, pushing the next one downstream
            push(out, queue.dequeue())
          } else {
            if (isClosed(in)) {
              // the queue is empty and the upstream has closed, the stage should be considered completed
              completeStage()
            } else if (hasBeenPulled(in)) {
              // upstream was already pulled, do nothing and wait for push
            } else {
              // pulling from upstream
              pull(in)
            }
          }
        }

        override def onUpstreamFinish(): Unit = {
          if (queue.isEmpty) {
            // upstream just closed and queue is empty, stage should be considered completed
            completeStage()
          } else {
            // upstream just closed but queue still contains elements, emit rest of elements and complete
            emitMultiple(out, queue.iterator, () => completeStage())
          }
        }
      }

      def bufferHandler: InHandler with OutHandler = new InHandler with OutHandler {
        override def onPush(): Unit = {
          val elem = grab(in)
          if (buffer.size + elem.size >= byteLimit) {
            // adding the incoming bytes to the buffer overflows the byte-limit
            // set handler to the continueHandler for upcoming messages
            setHandlers(in, out, continueHandler)

            // add bytes to buffer but prevent it from becoming larger than the limit
            val (toBuffer, overflow) = elem.splitAt(byteLimit - buffer.size)
            buffer ++= toBuffer

            if (isClosed(in) || hasBeenPulled(in)) {
              // input port is closed or has already been pulled, add the buffer to the queue
              queue.enqueue(buffer)
            } else {
              // push buffer downstream
              val toPush = buffer
              push(out, toPush)
            }

            // clear buffer variable as it is no longer useful
            buffer = null

            if (!overflow.isEmpty) {
              // add the overflowing bytes to the queue
              queue.enqueue(overflow)
            }

          } else {
            // add all incoming bytes to the buffer, it still has enough capacity, then pull upstream for more
            buffer ++= elem
            pull(in)
          }
        }

        override def onPull(): Unit = {
          if (!hasBeenPulled(in)) {
            // upstream has not yet been pulled, pulling it now
            pull(in)
          }
        }

        override def onUpstreamFinish(): Unit = {
          // upstream has closed, emitting buffer and complete stage afterwards
          emit(out, buffer, () => completeStage())
        }
      }

      // use the bufferHandler as the initial handler
      setHandlers(in, out, bufferHandler)
    }
  }
}
