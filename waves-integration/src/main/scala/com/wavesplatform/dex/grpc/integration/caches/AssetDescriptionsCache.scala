package com.wavesplatform.dex.grpc.integration.caches

import java.time.Duration

import cats.implicits._
import com.wavesplatform.dex.grpc.integration.dto.BriefAssetDescription
import com.wavesplatform.transaction.Asset.IssuedAsset

import scala.concurrent.{ExecutionContext, Future}

class AssetDescriptionsCache(load: IssuedAsset => Future[Option[BriefAssetDescription]], expiration: Duration)(
    implicit executionContext: ExecutionContext)
    extends CacheWithExpiration[Future, IssuedAsset, Option[BriefAssetDescription]](load, expiration)