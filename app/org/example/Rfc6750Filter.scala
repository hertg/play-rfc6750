package org.example

import org.apache.pekko.stream.Materializer
import play.api.Logging
import play.api.mvc.{EssentialAction, EssentialFilter}

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class Rfc6750Filter @Inject()(implicit mat: Materializer, ec: ExecutionContext) extends EssentialFilter with Logging {

  override def apply(next: EssentialAction): EssentialAction = Rfc6750Action(next)

}
