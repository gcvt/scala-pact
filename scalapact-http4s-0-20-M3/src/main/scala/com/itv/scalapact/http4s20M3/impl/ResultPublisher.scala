package com.itv.scalapact.http4s20M3.impl

import cats.effect._
import com.itv.scalapact.shared._
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


class ResultPublisher(fetcher: (SimpleRequest, IO[Client[IO]]) => IO[SimpleResponse]) extends IResultPublisher {

  val maxTotalConnections = 2

  override def publishResults(pactVerifyResults: List[PactVerifyResult], brokerPublishData: BrokerPublishData)(implicit sslContextMap: SslContextMap): Unit = {
    pactVerifyResults
      .map { result =>
        result.pact._links.flatMap(_.get("pb:publish-verification-results")).map(_.href) match {
          case Some(link) =>
            val success = !result.results.exists(_.result.isLeft)
            val request = SimpleRequest(link, "", HttpMethod.POST, Map("Content-Type" -> "application/json; charset=UTF-8"), body(brokerPublishData, success), None)

            SslContextMap(request)(
              sslContext =>
                simpleRequestWithoutFakeHeader => {
                  val client = BlazeClientBuilder(ExecutionContext.Implicits.global)
                    .withMaxTotalConnections(maxTotalConnections)
                    .withRequestTimeout(2.seconds)
                    .withSslContext(sslContext)
                  fetcher(simpleRequestWithoutFakeHeader, client)
                    .map { response =>
                      if (response.is2xx) {
                        PactLogger.message(
                          s"Verification results published for provider ${result.pact.provider} and consumer ${result.pact.consumer}"
                        )
                      } else {
                        PactLogger.error(s"Publish verification results failed with ${response.statusCode}".red)
                      }
                    }
                }
            )
          case None =>
            IO.pure(
              PactLogger
                .error("Unable to publish verification results as there is no pb:publish-verification-results link".red)
            )
        }
      }
      .sequence
      .map(_ => ())
      .unsafeRunSync()
  }
  private def body(brokerPublishData: BrokerPublishData, success: Boolean) = {
    val buildUrl = brokerPublishData.buildUrl.fold("")(u => s""", "buildUrl": "$u"""")
    Option(s"""{ "success": "$success", "providerApplicationVersion": "${brokerPublishData.providerVersion}"$buildUrl }""")
  }

}