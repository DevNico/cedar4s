package cedar4s.entities

import cedar4s.capability.Monad
import cedar4s.capability.instances.futureMonadError
import cedar4s.schema.CedarEntityUid

import java.util.concurrent.{ConcurrentHashMap, Executors, ScheduledExecutorService, TimeUnit}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.collection.JavaConverters._

/** Configuration for request batching/coalescing.
  *
  * @param windowMs
  *   Time window to collect requests before executing batch (milliseconds)
  * @param maxBatchSize
  *   Maximum number of entities to batch together
  * @param maxConcurrent
  *   Maximum concurrent batch operations per entity type
  */
final case class BatchConfig(
    windowMs: Int = 5,
    maxBatchSize: Int = 100,
    maxConcurrent: Int = 4
)

object BatchConfig {
  val default: BatchConfig = BatchConfig()
  val aggressive: BatchConfig = BatchConfig(windowMs = 10, maxBatchSize = 500, maxConcurrent = 8)
  val minimal: BatchConfig = BatchConfig(windowMs = 1, maxBatchSize = 50, maxConcurrent = 2)
  val disabled: BatchConfig = BatchConfig(windowMs = 0, maxBatchSize = 1, maxConcurrent = 1)
}

/** Batching decorator for EntityStore[Future].
  *
  * Coalesces concurrent requests for the same entities into batch operations.
  *
  * Note: This decorator is Future-specific. For other effect types, implement your own batching strategy.
  */
class BatchingEntityStore(
    underlying: EntityStore[Future],
    config: BatchConfig
)(implicit ec: ExecutionContext)
    extends EntityStore[Future] {

  private implicit val F: Monad[Future] = futureMonadError

  private val inFlight = new ConcurrentHashMap[CedarEntityUid, Future[Option[CedarEntity]]]()
  private val pendingBatch = new ConcurrentHashMap[CedarEntityUid, Promise[Option[CedarEntity]]]()
  private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
  @volatile private var batchScheduled = false
  private val batchLock = new Object()

  override def loadForRequest(principal: CedarPrincipal, resource: ResourceRef): Future[CedarEntities] = {
    loadResourceWithBatching(resource).map(principal.entities ++ _)
  }

  override def loadForBatch(principal: CedarPrincipal, resources: Seq[ResourceRef]): Future[CedarEntities] = {
    val allUids = resources.flatMap(r => r.uid.toList ++ r.parentUids).toSet
    loadEntities(allUids).map(principal.entities ++ _)
  }

  override def loadEntity(entityType: String, entityId: String): Future[Option[CedarEntity]] = {
    val uid = CedarEntityUid(entityType, entityId)
    if (config.windowMs <= 0 || config.maxBatchSize <= 1) {
      underlying.loadEntity(entityType, entityId)
    } else {
      loadWithBatching(uid)
    }
  }

  override def loadEntities(uids: Set[CedarEntityUid]): Future[CedarEntities] = {
    if (config.windowMs <= 0 || config.maxBatchSize <= 1) {
      underlying.loadEntities(uids)
    } else {
      Future.traverse(uids.toSeq)(loadWithBatching).map(entities => CedarEntities.fromSet(entities.flatten.toSet))
    }
  }

  override def loadEntityWithParents(
      entityType: String,
      entityId: String
  ): Future[Option[(CedarEntity, List[(String, String)])]] = {
    underlying.loadEntityWithParents(entityType, entityId)
  }

  def shutdown(): Unit = {
    scheduler.shutdown()
    pendingBatch.forEach((_, promise) => promise.tryFailure(new IllegalStateException("BatchingEntityStore shutdown")))
    pendingBatch.clear()
  }

  private def loadResourceWithBatching(resource: ResourceRef): Future[CedarEntities] = {
    val uidsToLoad = resource.uid.toList ++ resource.parentUids
    loadEntities(uidsToLoad.toSet)
  }

  private def loadWithBatching(uid: CedarEntityUid): Future[Option[CedarEntity]] = {
    val existingFlight = inFlight.get(uid)
    if (existingFlight != null) return existingFlight

    val existingPending = pendingBatch.get(uid)
    if (existingPending != null) return existingPending.future

    val promise = Promise[Option[CedarEntity]]()
    val previous = pendingBatch.putIfAbsent(uid, promise)
    if (previous != null) return previous.future

    scheduleBatchIfNeeded()
    if (pendingBatch.size() >= config.maxBatchSize) executeBatch()
    promise.future
  }

  private def scheduleBatchIfNeeded(): Unit = {
    batchLock.synchronized {
      if (!batchScheduled && config.windowMs > 0) {
        batchScheduled = true
        scheduler.schedule(
          new Runnable { def run(): Unit = executeBatch() },
          config.windowMs.toLong,
          TimeUnit.MILLISECONDS
        )
      }
    }
  }

  private def executeBatch(): Unit = {
    batchLock.synchronized { batchScheduled = false }
    val uidsToLoad = pendingBatch.keySet().asScala.toSet
    if (uidsToLoad.isEmpty) return

    val promises = uidsToLoad.flatMap(uid => Option(pendingBatch.remove(uid)).map(uid -> _)).toMap
    val loadFuture = underlying.loadEntities(uidsToLoad)

    promises.foreach { case (uid, promise) =>
      val entityFuture = loadFuture.map(entities => entities.find(uid))
      inFlight.put(uid, entityFuture)
    }

    loadFuture.onComplete { result =>
      result match {
        case scala.util.Success(entities) =>
          promises.foreach { case (uid, promise) => promise.trySuccess(entities.find(uid)) }
        case scala.util.Failure(ex) =>
          promises.foreach { case (_, promise) => promise.tryFailure(ex) }
      }
      promises.keys.foreach(inFlight.remove)
    }
  }
}
