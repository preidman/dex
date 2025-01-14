package com.wavesplatform.dex.settings

import cats.data.Validated.Valid
import cats.implicits._
import com.wavesplatform.dex.settings.AssetType.AssetType
import com.wavesplatform.dex.settings.FeeMode.FeeMode
import com.wavesplatform.settings.Constants
import com.wavesplatform.settings.utils.ConfigSettingsValidator
import com.wavesplatform.settings.utils.ConfigSettingsValidator.ErrorsListOr
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.Asset.{Waves, _}
import com.wavesplatform.transaction.assets.exchange.AssetPair
import monix.eval.Coeval
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.EnumerationReader._
import net.ceedubs.ficus.readers.ValueReader
import play.api.libs.json.{JsObject, Json}

object OrderFeeSettings {

  val totalWavesAmount: Long = Constants.UnitsInWave * Constants.TotalWaves

  sealed trait OrderFeeSettings {

    def getJson(matcherAccountFee: Long, ratesJson: JsObject): Coeval[JsObject] = Coeval.evalOnce {
      Json.obj(
        this match {
          case DynamicSettings(baseFee) =>
            "dynamic" -> Json.obj(
              "baseFee" -> (baseFee + matcherAccountFee),
              "rates"   -> ratesJson
            )
          case FixedSettings(defaultAssetId, minFee) =>
            "fixed" -> Json.obj(
              "assetId" -> AssetPair.assetIdStr(defaultAssetId),
              "minFee"  -> minFee
            )
          case PercentSettings(assetType, minFee) =>
            "percent" -> Json.obj(
              "type"   -> assetType,
              "minFee" -> minFee
            )
        }
      )
    }
  }

  case class DynamicSettings(baseFee: Long)                        extends OrderFeeSettings
  case class FixedSettings(defaultAssetId: Asset, minFee: Long)    extends OrderFeeSettings
  case class PercentSettings(assetType: AssetType, minFee: Double) extends OrderFeeSettings

  implicit val orderFeeSettingsReader: ValueReader[OrderFeeSettings] = { (cfg, path) =>
    val cfgValidator = ConfigSettingsValidator(cfg)

    def getPrefixByMode(mode: FeeMode): String = s"$path.$mode"

    def validateDynamicSettings: ErrorsListOr[DynamicSettings] = {
      cfgValidator.validateByPredicate[Long](s"${getPrefixByMode(FeeMode.DYNAMIC)}.base-fee")(
        predicate = fee => 0 < fee && fee <= totalWavesAmount,
        errorMsg = s"required 0 < base fee <= $totalWavesAmount"
      ) map DynamicSettings
    }

    def validateFixedSettings: ErrorsListOr[FixedSettings] = {

      val prefix         = getPrefixByMode(FeeMode.FIXED)
      val feeValidator   = cfgValidator.validateByPredicate[Long](s"$prefix.min-fee") _
      val assetValidated = cfgValidator.validate[Asset](s"$prefix.asset")

      val feeValidated = assetValidated match {
        case Valid(Waves) => feeValidator(fee => 0 < fee && fee <= totalWavesAmount, s"required 0 < fee <= $totalWavesAmount")
        case _            => feeValidator(_ > 0, "required 0 < fee")
      }

      (assetValidated, feeValidated) mapN FixedSettings
    }

    def validatePercentSettings: ErrorsListOr[PercentSettings] = {
      val prefix = getPrefixByMode(FeeMode.PERCENT)
      (
        cfgValidator.validate[AssetType](s"$prefix.asset-type"),
        cfgValidator.validatePercent(s"$prefix.min-fee")
      ) mapN PercentSettings
    }

    def getSettingsByMode(mode: FeeMode): ErrorsListOr[OrderFeeSettings] = mode match {
      case FeeMode.DYNAMIC => validateDynamicSettings
      case FeeMode.FIXED   => validateFixedSettings
      case FeeMode.PERCENT => validatePercentSettings
    }

    cfgValidator.validate[FeeMode](s"$path.mode").toEither >>= (mode => getSettingsByMode(mode).toEither) match {
      case Left(errorsAcc)         => throw new Exception(errorsAcc.mkString_(", "))
      case Right(orderFeeSettings) => orderFeeSettings
    }
  }
}

object AssetType extends Enumeration {
  type AssetType = Value

  val AMOUNT    = Value("amount")
  val PRICE     = Value("price")
  val SPENDING  = Value("spending")
  val RECEIVING = Value("receiving")
}

object FeeMode extends Enumeration {
  type FeeMode = Value

  val DYNAMIC = Value("dynamic")
  val FIXED   = Value("fixed")
  val PERCENT = Value("percent")
}
