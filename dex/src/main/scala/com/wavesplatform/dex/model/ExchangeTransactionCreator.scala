package com.wavesplatform.dex.model

import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.dex.model.ExchangeTransactionCreator._
import com.wavesplatform.dex.settings.AssetType.AssetType
import com.wavesplatform.dex.settings.OrderFeeSettings.PercentSettings
import com.wavesplatform.dex.settings.{AssetType, MatcherSettings}
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.features.FeatureProvider.FeatureProviderExt
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.state.Blockchain
import com.wavesplatform.state.diffs.FeeValidation
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.TxValidationError._
import com.wavesplatform.transaction.assets.exchange._

class ExchangeTransactionCreator(blockchain: Blockchain, matcherPrivateKey: KeyPair, matcherSettings: MatcherSettings) {

  private def calculateMatcherFee(buy: Order, sell: Order, executedAmount: Long, executedPrice: Long): (Long, Long) = {

    def calcFee(orderFee: Long, executedAmount: Long, totalAmount: Long): Long = {
      (BigInt(executedAmount) * orderFee / totalAmount).toLong
    }

    def getActualBuySellAmounts(assetType: AssetType, buyAmount: Long, buyPrice: Long, sellAmount: Long, sellPrice: Long): (Long, Long) = {

      val (buyAmt, sellAmt) = assetType match {
        case AssetType.AMOUNT    => buy.getReceiveAmount _ -> sell.getSpendAmount _
        case AssetType.PRICE     => buy.getSpendAmount _   -> sell.getReceiveAmount _
        case AssetType.RECEIVING => buy.getReceiveAmount _ -> sell.getReceiveAmount _
        case AssetType.SPENDING  => buy.getSpendAmount _   -> sell.getSpendAmount _
      }

      buyAmt(buyAmount, buyPrice).explicitGet() -> sellAmt(sellAmount, sellPrice).explicitGet()
    }

    matcherSettings.orderFee match {
      case PercentSettings(assetType, _) =>
        val (buyAmountExecuted, sellAmountExecuted) = getActualBuySellAmounts(assetType, executedAmount, executedPrice, executedAmount, executedPrice)
        val (buyAmountTotal, sellAmountTotal)       = getActualBuySellAmounts(assetType, buy.amount, buy.price, sell.amount, sell.price)

        (
          Math.min(buy.matcherFee, calcFee(buy.matcherFee, buyAmountExecuted, buyAmountTotal)),
          Math.min(sell.matcherFee, calcFee(sell.matcherFee, sellAmountExecuted, sellAmountTotal))
        )

      case _ => calcFee(buy.matcherFee, executedAmount, buy.amount) -> calcFee(sell.matcherFee, executedAmount, sell.amount)
    }
  }

  def createTransaction(submitted: AcceptedOrder, counter: LimitOrder, timestamp: Long): Either[ValidationError, ExchangeTransaction] = {

    val executedAmount    = AcceptedOrder.executedAmount(submitted, counter)
    val price             = counter.price
    val (buy, sell)       = Order.splitByType(submitted.order, counter.order)
    val (buyFee, sellFee) = calculateMatcherFee(buy, sell, executedAmount, price)

    // matcher always pays fee to the miners in Waves
    val txFee = minFee(blockchain, matcherPrivateKey, counter.order.assetPair, matcherSettings.exchangeTxBaseFee)

    if (blockchain.isFeatureActivated(BlockchainFeatures.SmartAccountTrading, blockchain.height))
      ExchangeTransactionV2.create(matcherPrivateKey, buy, sell, executedAmount, price, buyFee, sellFee, txFee, timestamp)
    else
      for {
        buyV1  <- toV1(buy)
        sellV1 <- toV1(sell)
        tx     <- ExchangeTransactionV1.create(matcherPrivateKey, buyV1, sellV1, executedAmount, price, buyFee, sellFee, txFee, timestamp)
      } yield tx
  }

  private def toV1(order: Order): Either[ValidationError, OrderV1] = order match {
    case x: OrderV1 => Right(x)
    case _          => Left(ActivationError("Smart Account Trading feature has not been activated yet"))
  }
}

object ExchangeTransactionCreator {

  type CreateTransaction = (AcceptedOrder, LimitOrder, Long) => Either[ValidationError, ExchangeTransaction]

  /**
    * This function is used for the following purposes:
    *
    *   1. Calculate matcher fee that CLIENT PAYS TO MATCHER for the order placement and covering matcher expenses (OrderValidator blockchainAware, base fee depends on order fee settings)
    *   2. Calculate transaction fee that MATCHER PAYS TO THE MINERS for issuing Exchange transaction (ExchangeTransactionCreator, base fee = matcherSettings.exchangeTxBaseFee)
    *
    * @see [[com.wavesplatform.transaction.smart.Verifier#verifyExchange verifyExchange]]
    */
  def minFee(blockchain: Blockchain, matcherAddress: Address, assetPair: AssetPair, baseFee: Long): Long = {

    def assetFee(assetId: Asset): Long = assetId.fold(0L) { asset =>
      if (blockchain hasAssetScript asset) FeeValidation.ScriptExtraFee
      else 0L
    }

    baseFee +
      minAccountFee(blockchain, matcherAddress) +
      assetFee(assetPair.amountAsset) +
      assetFee(assetPair.priceAsset)
  }

  def minAccountFee(blockchain: Blockchain, address: Address): Long = {
    if (blockchain hasScript address) FeeValidation.ScriptExtraFee else 0L
  }
}
