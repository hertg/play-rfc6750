package org.example

import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.http.Status.{BAD_REQUEST, OK, UNAUTHORIZED}
import play.api.libs.ws.DefaultBodyReadables.readableAsString
import play.api.libs.ws.DefaultBodyWritables.writeableOf_urlEncodedSimpleForm
import play.api.libs.ws.WSClient
import play.api.test.Helpers.{await, defaultAwaitTimeout}

class Rfc6750TokenSpec extends PlaySpec with GuiceOneServerPerSuite with ScalaFutures {

  val url: String = s"http://localhost:$port"

  def time(n: Int)(fn: => Unit): Unit = {
    // warmup
    1 to 1_000 foreach { _ => fn }

    val start = System.nanoTime()

    // execute
    1 to n foreach { _ => fn }

    val end = System.nanoTime()
    val all = end - start
    val avg = (all / n).toDouble / 1_000 / 1_000
    println(s"ALL ${all}ms")
    println(s"AVG ${avg - (avg % 0.01)}ms")
  }

  "speed test" in {
    time(10_000) {
      val wsClient = app.injector.instanceOf[WSClient]
      val response = await(wsClient.url(s"$url/echo").addQueryStringParameters("access_token" -> "from_query").get())
    }
  }

  "test echo endpoint" must {
    "parse token in query parameter" in {
      val wsClient = app.injector.instanceOf[WSClient]
      val response = await(wsClient.url(s"$url/echo").addQueryStringParameters("access_token" -> "from_query").get())
      response.status mustBe OK
      response.body mustBe "from_query"
    }

    "parse token in authorization header" in {
      val wsClient = app.injector.instanceOf[WSClient]
      val response = await(wsClient.url(s"$url/echo").withHttpHeaders("Authorization" -> "Bearer from_header").get())
      response.status mustBe OK
      response.body mustBe "from_header"
    }

    "parse token in form body" in {
      val wsClient = app.injector.instanceOf[WSClient]
      val response = await(wsClient.url(s"$url/echo").post(Map("access_token" -> "from_form_body")))
      response.status mustBe OK
      response.body mustBe "from_form_body"
    }

    "tokens in different places" must {
      "fail for querystring and header" in {
        val wsClient = app.injector.instanceOf[WSClient]
        val req = wsClient.url(s"$url/echo")
          .addQueryStringParameters("access_token" -> "from_query")
          .withHttpHeaders("Authorization" -> "Bearer from_header")
          .get()
        val response = await(req)
        response.status mustBe BAD_REQUEST
        response.body mustBe "invalid_request"
      }
      "fail for querystring and body" in {
        val wsClient = app.injector.instanceOf[WSClient]
        val req = wsClient.url(s"$url/echo")
          .addQueryStringParameters("access_token" -> "from_query")
          .post(Map("access_token" -> "from_form_body"))
        val response = await(req)
        response.status mustBe BAD_REQUEST
        response.body mustBe "invalid_request"
      }
      "fail for header and body" in {
        val wsClient = app.injector.instanceOf[WSClient]
        val req = wsClient.url(s"$url/echo")
          .withHttpHeaders("Authorization" -> "Bearer from_header")
          .post(Map("access_token" -> "from_form_body"))
        val response = await(req)
        response.status mustBe BAD_REQUEST
        response.body mustBe "invalid_request"
      }
    }

    "fail when token contains invalid characters" in {
      val wsClient = app.injector.instanceOf[WSClient]
      val response = await(wsClient.url(s"$url/echo").withHttpHeaders("Authorization" -> "Bearer fröm_header").get())
      response.status mustBe UNAUTHORIZED
      response.body mustBe "invalid_token"
    }
  }

}
