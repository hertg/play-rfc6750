package org.example

import org.scalatest.TestData
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.{GuiceOneServerPerSuite, GuiceOneServerPerTest}
import play.api.Application
import play.api.http.Status.{BAD_REQUEST, OK, UNAUTHORIZED}
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceApplicationLoader}
import play.api.libs.ws.DefaultBodyReadables.readableAsString
import play.api.libs.ws.DefaultBodyWritables.writeableOf_urlEncodedSimpleForm
import play.api.libs.ws.WSClient
import play.api.test.Helpers.{await, defaultAwaitTimeout}

class PerformanceSpec extends PlaySpec with GuiceOneServerPerTest with ScalaFutures {

  val url: String = s"http://localhost:$port"

  override def newAppForTest(testData: TestData): Application = {
    GuiceApplicationBuilder()
      .configure("play.filters.enabled" -> "org.example.Rfc6750Filter")
      .build()
  }

  private def measure(n: Int)(fn: => Unit): (Long, Double) = {
    // warmup
    1 to 1_000 foreach { _ => fn }

    val start = System.nanoTime()

    // execute
    1 to n foreach { _ => fn }

    val end = System.nanoTime()
    val all = end - start
    val avg = (all / n).toDouble / 1_000 / 1_000

    // all ms / avg ms
    (all / 1_000 / 1_000, avg - (avg % 0.01))
  }

  private def print_measurement(all: Long, avg: Double): Unit = {
    println("---")
    println(s"ALL ${all}ms")
    println(s"AVG ${avg}ms")
    println("---")
  }



  "speed test" in {
    val (all, avg) = measure(10_000) {
      val wsClient = app.injector.instanceOf[WSClient]
      val response = await(wsClient.url(s"$url/echo").post(Map("access_token" -> "from_form_body")))
    }
    print_measurement(all, avg)
  }

}
