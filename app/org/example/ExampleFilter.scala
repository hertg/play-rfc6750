package org.example

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.apache.pekko.util.ByteString
import play.api.Logging
import play.api.libs.streams.Accumulator
import play.api.mvc.{EssentialAction, EssentialFilter, RequestHeader, Result}

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ExampleFilter @Inject()(implicit mat: Materializer, ec: ExecutionContext) extends EssentialFilter with Logging {

  private final val BUFFER_SIZE: Int = 10240

  override def apply(next: EssentialAction): EssentialAction = {
    EssentialAction(request =>
      Accumulator(
        Flow[ByteString]
          // buffer the first N bytes into memory
          .via(new BufferFirstBytes(BUFFER_SIZE))
          // split the first element (buffer) from the rest of the stream
          .prefixAndTail(1)
          .mapAsync(1)(handle(request, next))
          .recover(???) // todo: if you want to catch exceptions that may be thrown in handle()
          .toMat(Sink.head)(Keep.right)
      ).recover(???) // todo: if you want to catch exceptions that may be thrown anywhere else (e.g. subsequent filters, controllers, etc)
    )
  }

  private def handle(request: RequestHeader, next: EssentialAction): ((Seq[ByteString], Source[ByteString, NotUsed])) => Future[Result] = {
    case (Seq(firstElement), rest) =>
      // got first element (firstElement: ByteString)

      // todo: extract information from raw body (firstElement)
      //  e.g. val token = extractFromByteString(firstElement)

      // todo: update request as required
      //  e.g. val req = request.addAttr(...)
      val req = request

      // create a sink with the updated request
      val sink = next(req).toSink

      // create a new source of the complete stream (first element + unconsumed source)
      val source = Source.single(firstElement).concat(rest)

      // run the source to the newly created sink
      source.runWith(sink)

    case (Seq(), _) =>
      Future.failed(new RuntimeException("Empty stream"))
  }

}
