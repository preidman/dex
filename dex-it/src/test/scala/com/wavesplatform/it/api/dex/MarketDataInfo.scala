package com.wavesplatform.it.api.dex

import play.api.libs.json.{Format, Json}

case class MarketDataInfo(matcherPublicKey: String, markets: Seq[MarketData])
object MarketDataInfo {
  implicit val format: Format[MarketDataInfo] = Json.format
}