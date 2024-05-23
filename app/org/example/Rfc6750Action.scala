package org.example

import org.apache.pekko.stream.*
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.util.ByteString
import play.api.http.HeaderNames.{AUTHORIZATION, CONTENT_TYPE}
import play.api.http.Status.{BAD_REQUEST, INTERNAL_SERVER_ERROR, UNAUTHORIZED}
import play.api.libs.streams.Accumulator
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Results.Status
import play.api.mvc.{EssentialAction, RequestHeader, Result}
import play.api.{Logger, Logging}

import java.net.{URLDecoder, URLEncoder}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

object Rfc6750Action {
  val RAW_ACCESS_TOKEN: TypedKey[String] = TypedKey("raw_access_token")
}

case class Error(status: Int, msg: String) extends Throwable

val onError: PartialFunction[Throwable, Result] = {
  case e: Error => new Status(e.status).apply(e.msg)
  case _ => new Status(INTERNAL_SERVER_ERROR)
}

/**
 * This action extracts a bearer token from the incoming request based on the
 * usage specification defined in https://datatracker.ietf.org/doc/html/rfc6750.
 */
class Rfc6750Action(next: EssentialAction)(implicit ec: ExecutionContext, mat: Materializer) extends EssentialAction with Logging {

  private val LOGGER: Logger = play.api.Logger(Rfc6750Action.getClass)
  private val BEARER_TOKEN_PREFIX = "bearer "
  private val QUERY_PARAMETER_ACCESS_TOKEN = "access_token"
  private val FORM_FIELD_ACCESS_TOKEN = "access_token"
  private val APPLICATION_FORM_URL_ENCODED = "application/x-www-form-urlencoded"

  override def apply(untaggedRequest: RequestHeader): Accumulator[ByteString, Result] = {
    try {
      run(untaggedRequest)
    } catch {
      case e: Throwable => Accumulator.done(onError(e))
    }
  }

  private def run(untaggedRequest: RequestHeader): Accumulator[ByteString, Result] = {
    val acc = {
      val fromAuthorizationHeader = AccessTokenActionHelper.extractTokenFromAuthorizationHeader(untaggedRequest)
      val fromQueryParameter = AccessTokenActionHelper.extractTokenFromQueryParameters(untaggedRequest)

      if (fromAuthorizationHeader.isDefined && fromQueryParameter.isDefined) {
        // An access_token was present in the Authorization header
        // and the query parameters, clients MUST NOT use more than one method to
        // transmit the token in each request,
        // see https://datatracker.ietf.org/doc/html/rfc6750#section-2
        throw Error(BAD_REQUEST, "invalid_request")
      }

      // if an access token is found as a Bearer Token in the Authorization header,
      // or if an access token was provided via the 'access_token' query parameter
      // add it to the request attrs
      val request = AccessTokenActionHelper.tagRequestWith(untaggedRequest, fromAuthorizationHeader.orElse(fromQueryParameter))

      if (AccessTokenActionHelper.hasInvalidContentType(request)) {
        throw Error(BAD_REQUEST, "invalid_request")
      }

      // this function exists purely to aid readability
      def continueWithoutParsingBody = next(request)

      request.contentType match {
        case Some(APPLICATION_FORM_URL_ENCODED) =>
          logger.trace(s"searching for access token in request body...")
          tagRequestFromFormBody(request, next, FORM_FIELD_ACCESS_TOKEN)
        case Some(content) =>
          logger.trace(s"not searching for access token in request body because sending access_token " +
            s"in request body with Content-Type '$content' is not supported, " +
            s"see https://datatracker.ietf.org/doc/html/rfc6750")
          continueWithoutParsingBody
        case None =>
          logger.trace(s"not searching for access token in request body because there is no Content-Type")
          continueWithoutParsingBody
      }
    }
    acc
  }

  private def tagRequestFromFormBody: (RequestHeader, EssentialAction, String) => Accumulator[ByteString, Result] =
    tagFromBody(AccessTokenActionHelper.extractTokenFromRawFormBody)

  /**
   * This custom graph stage will buffer the first N bytes and pass them downstream as the first element,
   * if the incoming data exceeds the byteLimit, anything overflowing the buffer is sent downstream as the second element,
   * any data coming in after those two elements are passed downstream as is.
   *
   * If the incoming data does not exceed to byteLimit and the upstream stops sending data, the data
   * is sent downstream as one element of size < N.
   *
   * @param byteLimit Amount of bytes that should be buffered for the first message downstream
   */
  private class BufferFirstBytes(byteLimit: Int) extends GraphStage[FlowShape[ByteString, ByteString]] {
    private val in = Inlet[ByteString]("BufferFirstBytes.in")
    private val out = Outlet[ByteString]("BufferFirstBytes.out")

    override def shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
      new GraphStageLogic(shape) {
        var buffer: ByteString = ByteString.empty
        val queue: mutable.Queue[ByteString] = mutable.Queue()

        override def postStop(): Unit = {
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

  private def tagFromBody(extractor: (ByteString, String) => Option[String])(request: RequestHeader, action: EssentialAction, tokenName: String): Accumulator[ByteString, Result] = {
    val flow = Flow[ByteString]
      // buffer the first N bytes into memory
      .via(new BufferFirstBytes(10240))
      // split the first element (buffer) from the rest of the stream
      .prefixAndTail(1)
      .mapAsync(1) {
        case (Seq(firstElement), rest) =>
          // extract the token from the first element
          val token = extractor(firstElement, tokenName)

          logger.trace(s"got first element (${firstElement.size} bytes), creating sink to pass stream to")

          if (token.isDefined && request.attrs.get(Rfc6750Action.RAW_ACCESS_TOKEN).isDefined) {
            // An access_token was present in the request header
            // AND the request body, clients MUST NOT use more than one method to
            // transmit the token in each request,
            // see https://datatracker.ietf.org/doc/html/rfc6750#section-2
            throw Error(BAD_REQUEST, "invalid_request")
          }

          // create a sink based on the extracted token from the first element
          val req = AccessTokenActionHelper.tagRequestWith(request, token)
          val sink = action(req).toSink

          // create a new source of the complete stream (first element + unconsumed source)
          val source = Source.single(firstElement).concat(rest)

          // run the source to the newly created sink
          source.runWith(sink)

        case (Seq(), _) =>
          Future.failed(new RuntimeException("Empty stream"))
      }
      .recover(onError) // catch exceptions during element processing
      .toMat(Sink.head)(Keep.right)

    Accumulator(flow)
  }

  private object AccessTokenActionHelper {

    def hasInvalidContentType(request: RequestHeader): Boolean = {
      // If the content type is none, but there's a content type header, that means
      // the content type failed to be parsed, therefore treat it like a blacklisted
      // content type just to be safe. Also, note we cannot use headers.hasHeader,
      // because this is intercepted by the Akka HTTP wrapper and will only turn true
      // if the content type was validly parsed.
      request.contentType.isEmpty && request.headers.toMap.contains(CONTENT_TYPE)
    }

    /**
     * Tag incoming request with the token provided as parameter
     *
     * @param request Incoming request
     * @param token   Optional Token
     * @return
     */
    def tagRequestWith(request: RequestHeader, token: => Option[String]): RequestHeader = {
      token.fold(request) { v =>
        logger.trace(s"an access_token was found and is added to the request attrs")
        request.addAttr(Rfc6750Action.RAW_ACCESS_TOKEN, v)
      }
    }

    def extractTokenFromAuthorizationHeader(request: RequestHeader): Option[String] = {
      request.headers.get(AUTHORIZATION)
        .filter(h => h.toLowerCase.startsWith(BEARER_TOKEN_PREFIX))
        .map(h => h.substring(BEARER_TOKEN_PREFIX.length))
        .filter { token =>
          val valid = token.nonEmpty && token.matches("[A-Za-z0-9\\-._~+/]+=*")
          if (!valid) {
            // A bearer token was provided in the Authorization header that
            // is not of a valid format, see https://datatracker.ietf.org/doc/html/rfc6750#section-2.1
            throw Error(UNAUTHORIZED, "invalid_token")
          }
          valid
        }
    }

    def extractTokenFromQueryParameters(request: RequestHeader): Option[String] =
      request.getQueryString(QUERY_PARAMETER_ACCESS_TOKEN)

    /**
     * Does a very simple parse of the form body to find the token, if it exists.
     */
    def extractTokenFromRawFormBody(body: ByteString, tokenName: String): Option[String] = {
      val tokenEquals = ByteString(URLEncoder.encode(tokenName, "utf-8")) ++ ByteString('=')

      // First check if it's the first token
      if (body.startsWith(tokenEquals)) {
        Some(URLDecoder.decode(body.drop(tokenEquals.size).takeWhile(_ != '&').utf8String, "utf-8"))
      } else {
        val andTokenEquals = ByteString('&') ++ tokenEquals
        val index = body.indexOfSlice(andTokenEquals)
        if (index == -1) {
          None
        } else {
          Some(URLDecoder.decode(body.drop(index + andTokenEquals.size).takeWhile(_ != '&').utf8String, "utf-8"))
        }
      }
    }

  }
}