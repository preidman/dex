package com.wavesplatform.it.api.dex

import play.api.libs.json.{Format, Json}

case class AssetDecimalsInfo(decimals: Byte)
object AssetDecimalsInfo {
  implicit val format: Format[AssetDecimalsInfo] = Json.format
}