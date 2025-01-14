package com.wavesplatform.dex

import java.io.File
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.pattern.{AskTimeoutException, ask, gracefulStop}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.wavesplatform.account.{Address, PublicKey}
import com.wavesplatform.api.http.{ApiRoute, CompositeHttpService => _}
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.database._
import com.wavesplatform.dex.Matcher.Status
import com.wavesplatform.dex.api.http.CompositeHttpService
import com.wavesplatform.dex.api.{MatcherApiRoute, MatcherApiRouteV1, OrderBookSnapshotHttpCache}
import com.wavesplatform.dex.caches.{AssetDecimalsCache, MatchingRulesCache, RateCache}
import com.wavesplatform.dex.db.{AssetPairsDB, OrderBookSnapshotDB, OrderDB}
import com.wavesplatform.dex.error.{ErrorFormatterContext, MatcherError}
import com.wavesplatform.dex.history.HistoryRouter
import com.wavesplatform.dex.market.OrderBookActor.MarketStatus
import com.wavesplatform.dex.market._
import com.wavesplatform.dex.model._
import com.wavesplatform.dex.queue._
import com.wavesplatform.dex.settings.MatcherSettings
import com.wavesplatform.extensions.{Context, Extension}
import com.wavesplatform.state.VolumeAndFee
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.assets.exchange.{AssetPair, Order}
import com.wavesplatform.utils.{ErrorStartingMatcher, ScorexLogging, forceStopApplication}
import net.ceedubs.ficus.Ficus._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class Matcher(context: Context) extends Extension with ScorexLogging {

  private val settings = context.settings.config.as[MatcherSettings]("waves.dex")

  private val matcherKeyPair = (
    for {
      address <- Address.fromString(settings.account)
      pk      <- context.wallet.privateKeyAccount(address)
    } yield pk
  ).explicitGet()

  private def matcherPublicKey: PublicKey = matcherKeyPair

  private implicit val as: ActorSystem                 = context.actorSystem
  private implicit val materializer: ActorMaterializer = ActorMaterializer()

  import as.dispatcher

  private val status: AtomicReference[Status] = new AtomicReference(Status.Starting)

  private val blacklistedAssets: Set[IssuedAsset] = settings.blacklistedAssets
    .map { assetId =>
      AssetPair.extractAssetId(assetId) match {
        case Success(Waves)          => throw new IllegalArgumentException("Can't blacklist the main coin")
        case Success(a: IssuedAsset) => a
        case Failure(e)              => throw new IllegalArgumentException("Can't parse asset id", e)
      }
    }

  private lazy val blacklistedAddresses = settings.blacklistedAddresses.map(Address.fromString(_).explicitGet())

  private val pairBuilder        = new AssetPairBuilder(settings, context.blockchain, blacklistedAssets)
  private val transactionCreator = new ExchangeTransactionCreator(context.blockchain, matcherKeyPair, settings)
  private val orderBooks         = new AtomicReference(Map.empty[AssetPair, Either[Unit, ActorRef]])

  private val assetDecimalsCache                           = new AssetDecimalsCache(context.blockchain)
  private implicit val errorContext: ErrorFormatterContext = (asset: Asset) => assetDecimalsCache.get(asset)

  private val orderBookCache     = new ConcurrentHashMap[AssetPair, OrderBook.AggregatedSnapshot](1000, 0.9f, 10)
  private val matchingRulesCache = new MatchingRulesCache(settings, context.blockchain)
  private val rateCache          = RateCache(db)

  private val orderBooksSnapshotCache =
    new OrderBookSnapshotHttpCache(
      settings.orderBookSnapshotHttpCache,
      context.time,
      assetDecimalsCache.get,
      assetPair => Option { orderBookCache.get(assetPair) }
    )

  private val marketStatuses                                     = new ConcurrentHashMap[AssetPair, MarketStatus](1000, 0.9f, 10)
  private val getMarketStatus: AssetPair => Option[MarketStatus] = p => Option(marketStatuses.get(p))

  private def updateOrderBookCache(assetPair: AssetPair)(newSnapshot: OrderBook.AggregatedSnapshot): Unit = {
    orderBookCache.put(assetPair, newSnapshot)
    orderBooksSnapshotCache.invalidate(assetPair)
  }

  private def orderBookProps(assetPair: AssetPair, matcherActor: ActorRef): Props = {
    matchingRulesCache.setCurrentMatchingRuleForNewOrderBook(assetPair)
    OrderBookActor.props(
      matcherActor,
      addressActors,
      orderBookSnapshotStore,
      assetPair,
      updateOrderBookCache(assetPair),
      marketStatuses.put(assetPair, _),
      settings,
      transactionCreator.createTransaction,
      context.time,
      matchingRules = matchingRulesCache.getMatchingRules(assetPair),
      updateCurrentMatchingRules = actualMatchingRule => matchingRulesCache.updateCurrentMatchingRule(assetPair, actualMatchingRule),
      normalizeMatchingRule = denormalizedMatchingRule => denormalizedMatchingRule.normalize(assetPair, context.blockchain),
    )
  }

  private val matcherQueue: MatcherQueue = settings.eventsQueue.tpe match {
    case "local" =>
      log.info("Events will be stored locally")
      new LocalMatcherQueue(settings.eventsQueue.local, new LocalQueueStore(db), context.time)

    case "kafka" =>
      log.info("Events will be stored in Kafka")
      new KafkaMatcherQueue(settings.eventsQueue.kafka)(materializer)

    case x => throw new IllegalArgumentException(s"Unknown queue type: $x")
  }

  private def validateOrder(o: Order): Either[MatcherError, Order] =
    for {
      _ <- OrderValidator.matcherSettingsAware(matcherPublicKey,
                                               blacklistedAddresses,
                                               blacklistedAssets,
                                               settings,
                                               rateCache,
                                               assetDecimalsCache.get)(o)
      _ <- OrderValidator.timeAware(context.time)(o)
      _ <- OrderValidator.marketAware(settings.orderFee, settings.deviation, getMarketStatus(o.assetPair), rateCache)(o)
      _ <- OrderValidator.blockchainAware(
        context.blockchain,
        transactionCreator.createTransaction,
        matcherPublicKey.toAddress,
        context.time,
        settings.orderFee,
        settings.orderRestrictions,
        rateCache,
        assetDecimalsCache.get
      )(o)
      _ <- pairBuilder.validateAssetPair(o.assetPair)
      _ <- OrderValidator.tickSizeAware(matchingRulesCache.getNormalizedRuleForNextOrder(o.assetPair, matcherQueue.lastProcessedOffset).tickSize)(o)
    } yield o

  lazy val matcherApiRoutes: Seq[ApiRoute] = {
    val keyHashStr = context.settings.config.getString("waves.rest-api.api-key-hash")

    Seq(
      MatcherApiRoute(
        pairBuilder,
        matcherPublicKey,
        matcher,
        addressActors,
        matcherQueue.storeEvent,
        p => Option(orderBooks.get()).flatMap(_.get(p)),
        p => Option(marketStatuses.get(p)),
        getActualTickSize = assetPair => matchingRulesCache.getDenormalizedRuleForNextOrder(assetPair, matcherQueue.lastProcessedOffset).tickSize,
        validateOrder,
        orderBooksSnapshotCache,
        settings,
        () => status.get(),
        db,
        context.time,
        () => matcherQueue.lastProcessedOffset,
        () => matcherQueue.lastEventOffset,
        ExchangeTransactionCreator.minAccountFee(context.blockchain, matcherPublicKey.toAddress),
        keyHashStr,
        rateCache,
        settings.allowedOrderVersions.filter(OrderValidator.checkOrderVersion(_, context.blockchain).isRight)
      ),
      MatcherApiRouteV1(
        pairBuilder,
        orderBooksSnapshotCache,
        () => status.get(),
        keyHashStr,
        settings
      )
    )
  }

  lazy val matcherApiTypes: Set[Class[_]] = Set(
    classOf[MatcherApiRoute],
    classOf[MatcherApiRouteV1],
  )

  private val snapshotsRestore = Promise[Unit]()

  private lazy val db                  = openDB(settings.dataDir)
  private lazy val assetPairsDB        = AssetPairsDB(db)
  private lazy val orderBookSnapshotDB = OrderBookSnapshotDB(db)
  private lazy val orderDB             = OrderDB(settings, db)

  lazy val orderBookSnapshotStore: ActorRef = {
    context.actorSystem.actorOf(OrderBookSnapshotStoreActor.props(orderBookSnapshotDB), "order-book-snapshot-store")
  }

  private val pongTimeout = new Timeout(settings.processConsumedTimeout * 2)

  lazy val matcher: ActorRef = context.actorSystem.actorOf(
    MatcherActor.props(
      settings,
      assetPairsDB, {
        case Left(msg) =>
          log.error(s"Can't start matcher: $msg")
          forceStopApplication(ErrorStartingMatcher)

        case Right((self, processedOffset)) =>
          snapshotsRestore.trySuccess(())
          matcherQueue.startConsume(
            processedOffset + 1,
            xs => {
              if (xs.isEmpty) Future.successful(())
              else {
                val assetPairs: Set[AssetPair] = xs.map { eventWithMeta =>
                  log.debug(s"Consumed $eventWithMeta")

                  self ! eventWithMeta
                  eventWithMeta.event.assetPair
                }(collection.breakOut)

                self
                  .ask(MatcherActor.PingAll(assetPairs))(pongTimeout)
                  .recover {
                    case NonFatal(e) => log.error("PingAll is timed out!", e)
                  }
                  .map(_ => ())
              }
            }
          )
      },
      orderBooks,
      orderBookProps,
      context.blockchain.assetDescription
    ),
    MatcherActor.name
  )

  private lazy val historyRouter = settings.orderHistory.map { orderHistorySettings =>
    context.actorSystem.actorOf(HistoryRouter.props(context.blockchain, settings.postgresConnection, orderHistorySettings), "history-router")
  }

  private lazy val addressActors =
    context.actorSystem.actorOf(
      Props(
        new AddressDirectory(
          context.spendableBalanceChanged,
          settings,
          orderDB,
          (address, startSchedules) =>
            Props(
              new AddressActor(
                address,
                context.utx.spendableBalance(address, _),
                5.seconds,
                context.time,
                orderDB,
                id => context.blockchain.filledVolumeAndFee(id) != VolumeAndFee.empty,
                matcherQueue.storeEvent,
                orderBookCache.get,
                startSchedules
              )
          ),
          historyRouter
        )
      ),
      "addresses"
    )

  @volatile var matcherServerBinding: ServerBinding = _

  override def shutdown(): Future[Unit] = Future {
    log.info("Shutting down matcher")
    setStatus(Status.Stopping)

    Await.result(matcherServerBinding.unbind(), 10.seconds)

    val stopMatcherTimeout = 5.minutes
    matcherQueue.close(stopMatcherTimeout)

    orderBooksSnapshotCache.close()
    Await.result(gracefulStop(matcher, stopMatcherTimeout, MatcherActor.Shutdown), stopMatcherTimeout)
    materializer.shutdown()
    log.debug("Matcher's actor system has been shut down")
    db.close()
    log.debug("Matcher's database closed")
    log.info("Matcher shutdown successful")
  }

  private def checkDirectory(directory: File): Unit = if (!directory.exists()) {
    log.error(s"Failed to create directory '${directory.getPath}'")
    sys.exit(1)
  }

  override def start(): Unit = {
    val journalDir  = new File(settings.journalDataDir)
    val snapshotDir = new File(settings.snapshotsDataDir)
    journalDir.mkdirs()
    snapshotDir.mkdirs()

    checkDirectory(journalDir)
    checkDirectory(snapshotDir)

    log.info(s"Starting matcher on: ${settings.bindAddress}:${settings.port} ...")

    val combinedRoute = new CompositeHttpService(matcherApiTypes, matcherApiRoutes, context.settings.restAPISettings).compositeRoute
    matcherServerBinding = Await.result(Http().bindAndHandle(combinedRoute, settings.bindAddress, settings.port), 5.seconds)

    log.info(s"Matcher bound to ${matcherServerBinding.localAddress}")
    context.actorSystem.actorOf(
      ExchangeTransactionBroadcastActor
        .props(
          settings.exchangeTransactionBroadcast,
          context.time,
          tx => context.utx.putIfNew(tx).resultE.isRight,
          context.blockchain.containsTransaction(_),
          txs => txs.foreach(context.broadcastTransaction)
        ),
      "exchange-transaction-broadcast"
    )

    context.actorSystem.actorOf(MatcherTransactionWriter.props(db, settings), MatcherTransactionWriter.name)

    val startGuard = for {
      _ <- waitSnapshotsRestored(settings.snapshotsLoadingTimeout)
      deadline = settings.startEventsProcessingTimeout.fromNow
      lastOffsetQueue <- getLastOffset(deadline)
      _ = log.info(s"Last queue offset is $lastOffsetQueue")
      _ <- waitOffsetReached(lastOffsetQueue, deadline)
      _ = log.info("Last offset has been reached, notify addresses")
    } yield addressActors ! AddressDirectory.StartSchedules

    startGuard.onComplete {
      case Success(_) => setStatus(Status.Working)
      case Failure(e) =>
        log.error(s"Can't start matcher: ${e.getMessage}", e)
        forceStopApplication(ErrorStartingMatcher)
    }
  }

  private def setStatus(newStatus: Status): Unit = {
    status.set(newStatus)
    log.info(s"Status now is $newStatus")
  }

  private def waitSnapshotsRestored(timeout: FiniteDuration): Future[Unit] = {
    val failure = Promise[Unit]()
    context.actorSystem.scheduler.scheduleOnce(timeout) {
      failure.failure(new TimeoutException(s"Can't restore snapshots in ${timeout.toSeconds} seconds"))
    }

    Future.firstCompletedOf[Unit](List(snapshotsRestore.future, failure.future))
  }

  private def getLastOffset(deadline: Deadline): Future[QueueEventWithMeta.Offset] = matcherQueue.lastEventOffset.recoverWith {
    case _: AskTimeoutException =>
      if (deadline.isOverdue()) Future.failed(new TimeoutException("Can't get last offset from queue"))
      else getLastOffset(deadline)
  }

  private def waitOffsetReached(lastQueueOffset: QueueEventWithMeta.Offset, deadline: Deadline): Future[Unit] = {
    def loop(p: Promise[Unit]): Unit = {
      val currentOffset = matcherQueue.lastProcessedOffset
      log.trace(s"offsets: $currentOffset >= $lastQueueOffset, deadline: ${deadline.isOverdue()}")
      if (currentOffset >= lastQueueOffset) p.success(())
      else if (deadline.isOverdue())
        p.failure(new TimeoutException(s"Can't process all events in ${settings.startEventsProcessingTimeout.toMinutes} minutes"))
      else context.actorSystem.scheduler.scheduleOnce(5.second)(loop(p))
    }

    val p = Promise[Unit]()
    loop(p)
    p.future
  }
}

object Matcher extends ScorexLogging {

  type StoreEvent = QueueEvent => Future[Option[QueueEventWithMeta]]

  sealed trait Status
  object Status {
    case object Starting extends Status
    case object Working  extends Status
    case object Stopping extends Status
  }
}
