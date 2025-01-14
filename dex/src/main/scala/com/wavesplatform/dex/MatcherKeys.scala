package com.wavesplatform.dex

import java.nio.ByteBuffer

import com.google.common.primitives.{Ints, Longs, Shorts}
import com.wavesplatform.account.Address
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.database.Key
import com.wavesplatform.dex.model.OrderInfo.FinalOrderInfo
import com.wavesplatform.dex.model.{OrderBook, OrderInfo}
import com.wavesplatform.dex.queue.{QueueEvent, QueueEventWithMeta}
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.assets.exchange._

import scala.collection.mutable

object MatcherKeys {
  import com.wavesplatform.database.KeyHelpers._

  val version: Key[Int] = intKey("matcher-version", 0, default = 1)

  def order(orderId: ByteStr): Key[Option[Order]] = Key.opt(
    "matcher-order",
    bytes(1, orderId.arr),
    xs => Order.fromBytes(xs.head, xs.tail),
    o => o.version +: o.bytes()
  )

  def orderInfo(orderId: ByteStr): Key[Option[FinalOrderInfo]] =
    Key.opt("matcher-order-info", bytes(2, orderId.arr), OrderInfo.decode, OrderInfo.encode)

  // activeOrdersOldestSeqNr = 3
  // activeOrdersSeqNr = 4
  // activeOrders = 5
  // openVolume = 6
  // openVolumeSeqNr = 7
  // openVolumeAsset = 8

  def orderTxIdsSeqNr(orderId: ByteStr): Key[Int]           = bytesSeqNr("matcher-order-tx-ids-seq-nr", 9, orderId.arr)
  def orderTxId(orderId: ByteStr, seqNr: Int): Key[ByteStr] = Key("matcher-order-tx-id", hBytes(10, seqNr, orderId.arr), ByteStr(_), _.arr)

  def exchangeTransaction(txId: ByteStr): Key[Option[ExchangeTransaction]] =
    Key.opt("matcher-exchange-transaction", bytes(11, txId.arr), ExchangeTransaction.parse(_).get, _.bytes())

  // activeOrdersSize = 12
  // activeOrdersSeqNr = 13

  def finalizedCommonSeqNr(address: Address): Key[Int] =
    bytesSeqNr("matcher-finalized-common-seq-nr", 14, address.bytes.arr)

  def finalizedCommon(address: Address, seqNr: Int): Key[Option[Order.Id]] =
    Key.opt("matcher-finalized-common", bytes(15, address.bytes.arr ++ Ints.toByteArray(seqNr)), ByteStr(_), _.arr)

  def finalizedPairSeqNr(address: Address, pair: AssetPair): Key[Int] =
    bytesSeqNr("matcher-finalized-pair-seq-nr", 16, address.bytes.arr ++ pair.bytes)

  def finalizedPair(address: Address, pair: AssetPair, seqNr: Int): Key[Option[Order.Id]] =
    Key.opt("matcher-finalized-pair", bytes(17, address.bytes.arr ++ pair.bytes ++ Ints.toByteArray(seqNr)), ByteStr(_), _.arr)

  def lastCommandTimestamp(address: Address): Key[Option[Long]] =
    Key.opt("matcher-last-command-timestamp", bytes(18, address.bytes.arr), Longs.fromByteArray, Longs.toByteArray)

  // lq - local queue
  val lqOldestIdx: Key[Long] = longKey("lq-oldest-idx", 19: Short, default = -1)
  val lqNewestIdx: Key[Long] = longKey("lq-newest-idx", 20: Short, default = -1)

  val LqElementPrefix: Short            = 21
  val LqElementPrefixBytes: Array[Byte] = Shorts.toByteArray(LqElementPrefix)
  val LqElementKeyName: String          = "lq-element"

  def lpqElement(idx: Long): Key[Option[QueueEventWithMeta]] =
    Key.opt(
      LqElementKeyName,
      bytes(LqElementPrefix, Longs.toByteArray(idx)),
      xs => QueueEventWithMeta(idx, Longs.fromByteArray(xs.take(8)), QueueEvent.fromBytes(xs.drop(8))),
      QueueEventWithMeta.toBytes(_).drop(8)
    )

  val ratePrefix: Short = 22
  def rate(asset: IssuedAsset): Key[Double] = {
    import java.lang.Double._
    Key(
      keyName = "matcher-rate",
      key = bytes(ratePrefix, asset.id.arr),
      parser = arr => longBitsToDouble(Longs.fromByteArray(arr)),
      encoder = double => Longs.toByteArray(doubleToLongBits(double))
    )
  }

  val AssetPairsPrefix: Short = 23
  def assetPair(pair: AssetPair): Key[Unit] =
    Key("matcher-asset-pair", bytes(AssetPairsPrefix, pair.bytes), _ => (), _ => Array.emptyByteArray)

  val OrderBookSnapshotOffsetPrefix: Short = 24
  def orderBookSnapshotOffset(pair: AssetPair): Key[Option[Long]] =
    Key.opt("matcher-ob-snapshot-offset", bytes(OrderBookSnapshotOffsetPrefix, pair.bytes), Longs.fromByteArray, Longs.toByteArray)

  val OrderBookSnapshotPrefix: Short = 25
  def orderBookSnapshot(pair: AssetPair): Key[Option[OrderBook.Snapshot]] =
    Key.opt(
      "matcher-ob-snapshot",
      bytes(OrderBookSnapshotPrefix, pair.bytes),
      xs => OrderBook.Snapshot.fromBytes(ByteBuffer.wrap(xs)),
      x => {
        val r = new mutable.ArrayBuilder.ofByte
        OrderBook.Snapshot.serialize(r, x)
        r.result()
      }
    )
}
