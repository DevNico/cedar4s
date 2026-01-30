package cedar4s.bench

import cedar4s.Bijection
import cedar4s.capability.Applicative
import cedar4s.capability.instances.{futureMonadError, futureSync}
import cedar4s.client.{CedarContext, CedarDecision, CedarEngine, CedarRequest}
import cedar4s.entities.*
import cedar4s.schema.CedarEntityUid
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.concurrent.blocking
import scala.compiletime.uninitialized
import scala.util.Random

/** Integration benchmarks that exercise the real request path with PostgreSQL.
  *
  * These benchmarks measure:
  *   - JDBC + connection pool overhead
  *   - realistic entity fetch costs
  *   - end-to-end authorization with DB-backed EntityStore
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class IntegrationEntityStoreBench {

  given ExecutionContext = ExecutionContext.global

  @Param(Array("small", "medium"))
  var scale: String = uninitialized

  @Param(Array("8", "16"))
  var poolSize: String = uninitialized

  var container: PostgreSQLContainer[?] = uninitialized
  var dataSource: HikariDataSource = uninitialized
  var store: EntityStore[Future] = uninitialized
  var engine: CedarEngine[Future] = uninitialized

  var dataset: IntegrationDataset = uninitialized

  private val streamIndex = new AtomicInteger(0)
  private val userIndex = new AtomicInteger(0)
  private val docIndex = new AtomicInteger(0)

  @Setup(Level.Trial)
  def setup(): Unit = {
    container = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
    container.withDatabaseName("cedar_bench")
    container.withUsername("cedar")
    container.withPassword("cedar")
    container.start()

    val hikari = new HikariConfig()
    hikari.setJdbcUrl(container.getJdbcUrl)
    hikari.setUsername(container.getUsername)
    hikari.setPassword(container.getPassword)
    hikari.setDriverClassName("org.postgresql.Driver")
    hikari.setMaximumPoolSize(poolSize.toInt)
    hikari.setMinimumIdle(math.min(2, poolSize.toInt))
    hikari.setPoolName(s"cedar-bench-$poolSize")
    hikari.setAutoCommit(true)
    dataSource = new HikariDataSource(hikari)

    DatabaseSchema.create(dataSource)
    dataset = IntegrationDataBuilder.seed(dataSource, IntegrationConfig.forScale(scale), seed = 42)

    store = JdbcEntityStore.build(dataSource)
    engine = CedarEngine.fromResources(
      policiesPath = "policies",
      policyFiles = Seq("ownership.cedar", "hierarchy.cedar")
    )
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    if (dataSource != null) dataSource.close()
    if (container != null) container.stop()
  }

  // ---------------------------------------------------------------------------
  // Single entity access
  // ---------------------------------------------------------------------------

  @Benchmark
  def fetchUser_Mixed_FromDb(blackhole: Blackhole): Unit = {
    val id = dataset.userIdStream(nextIndex(userIndex, dataset.userIdStream.length))
    val entity = Await.result(store.loadEntity("Bench::User", id), 2.seconds)
    blackhole.consume(entity)
  }

  @Benchmark
  def fetchDocument_Mixed_FromDb(blackhole: Blackhole): Unit = {
    val id = dataset.docIdStream(nextIndex(docIndex, dataset.docIdStream.length))
    val entity = Await.result(store.loadEntity("Bench::Document", id), 2.seconds)
    blackhole.consume(entity)
  }

  // ---------------------------------------------------------------------------
  // Batch access
  // ---------------------------------------------------------------------------

  @Benchmark
  @OperationsPerInvocation(50)
  def fetchBatch_50Docs_FromDb(blackhole: Blackhole): Unit = {
    val entities = Await.result(store.loadEntities(dataset.batchDocUids50), 5.seconds)
    blackhole.consume(entities)
  }

  // ---------------------------------------------------------------------------
  // Resource loading
  // ---------------------------------------------------------------------------

  @Benchmark
  def loadForRequest_Shallow_FromDb(blackhole: Blackhole): Unit = {
    val entities = Await.result(store.loadForRequest(dataset.defaultPrincipal, dataset.shallowResource), 5.seconds)
    blackhole.consume(entities)
  }

  @Benchmark
  def loadForRequest_Deep_FromDb(blackhole: Blackhole): Unit = {
    val entities = Await.result(store.loadForRequest(dataset.defaultPrincipal, dataset.deepResource), 5.seconds)
    blackhole.consume(entities)
  }

  @Benchmark
  @OperationsPerInvocation(100)
  def loadForBatch_Mixed_FromDb(blackhole: Blackhole): Unit = {
    val resources = dataset.resourceStream.take(100).toSeq
    val entities = Await.result(store.loadForBatch(dataset.defaultPrincipal, resources), 10.seconds)
    blackhole.consume(entities)
  }

  // ---------------------------------------------------------------------------
  // End-to-end authorization
  // ---------------------------------------------------------------------------

  @Benchmark
  def fullAuth_Mixed_WithPrincipalFetch(blackhole: Blackhole): Unit = {
    val idx = nextIndex(streamIndex, dataset.requestStream.length)
    val request = dataset.requestStream(idx)
    val resource = dataset.resourceStream(idx)
    val principalUid = request.principal
    val principalEntity = Await.result(store.loadEntity(principalUid.entityType, principalUid.entityId), 2.seconds)
    val principal = CedarPrincipal(
      uid = principalUid,
      entities = principalEntity.map(CedarEntities(_)).getOrElse(CedarEntities.empty)
    )
    val entities = Await.result(store.loadForRequest(principal, resource), 5.seconds)
    val decision = Await.result(engine.authorize(request, entities), 2.seconds)
    blackhole.consume(decision)
  }

  @Benchmark
  def fullAuth_Mixed_PreloadedPrincipal(blackhole: Blackhole): Unit = {
    val idx = nextIndex(streamIndex, dataset.requestStream.length)
    val request = dataset.requestStream(idx)
    val resource = dataset.resourceStream(idx)
    val principal = dataset.principalStream(idx)
    val entities = Await.result(store.loadForRequest(principal, resource), 5.seconds)
    val decision = Await.result(engine.authorize(request, entities), 2.seconds)
    blackhole.consume(decision)
  }

  private def nextIndex(counter: AtomicInteger, size: Int): Int = {
    val idx = counter.getAndIncrement()
    if (size == 0) 0 else Math.floorMod(idx, size)
  }
}

private final case class IntegrationConfig(
    orgCount: Int,
    usersPerOrg: Int,
    groupsPerOrg: Int,
    foldersPerOrg: Int,
    docsPerOrg: Int,
    folderDepth: Int,
    hotFraction: Double,
    hotTrafficShare: Double
)

private object IntegrationConfig {
  def forScale(scale: String): IntegrationConfig = scale match {
    case "medium" =>
      IntegrationConfig(
        orgCount = 15,
        usersPerOrg = 200,
        groupsPerOrg = 8,
        foldersPerOrg = 80,
        docsPerOrg = 800,
        folderDepth = 4,
        hotFraction = 0.02,
        hotTrafficShare = 0.85
      )
    case _ =>
      IntegrationConfig(
        orgCount = 6,
        usersPerOrg = 80,
        groupsPerOrg = 5,
        foldersPerOrg = 40,
        docsPerOrg = 300,
        folderDepth = 3,
        hotFraction = 0.02,
        hotTrafficShare = 0.80
      )
  }
}

private final case class IntegrationDataset(
    defaultPrincipal: CedarPrincipal,
    principalStream: Array[CedarPrincipal],
    requestStream: Array[CedarRequest],
    resourceStream: Array[ResourceRef],
    userIdStream: Array[String],
    docIdStream: Array[String],
    batchDocUids50: Set[CedarEntityUid],
    shallowResource: ResourceRef,
    deepResource: ResourceRef
)

private object DatabaseSchema {
  private val statements = Seq(
    """
    CREATE TABLE organizations (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL
    )
    """.trim,
    """
    CREATE TABLE groups (
      id TEXT PRIMARY KEY,
      org_id TEXT NOT NULL REFERENCES organizations(id)
    )
    """.trim,
    """
    CREATE TABLE users (
      id TEXT PRIMARY KEY,
      org_id TEXT NOT NULL REFERENCES organizations(id),
      name TEXT NOT NULL,
      email TEXT NOT NULL,
      is_admin BOOLEAN NOT NULL
    )
    """.trim,
    """
    CREATE TABLE user_groups (
      user_id TEXT NOT NULL REFERENCES users(id),
      group_id TEXT NOT NULL REFERENCES groups(id)
    )
    """.trim,
    """
    CREATE TABLE folders (
      id TEXT PRIMARY KEY,
      org_id TEXT NOT NULL REFERENCES organizations(id),
      parent_type TEXT NOT NULL,
      parent_id TEXT NOT NULL,
      name TEXT NOT NULL
    )
    """.trim,
    """
    CREATE TABLE folder_viewers (
      folder_id TEXT NOT NULL REFERENCES folders(id),
      user_id TEXT NOT NULL REFERENCES users(id)
    )
    """.trim,
    """
    CREATE TABLE documents (
      id TEXT PRIMARY KEY,
      org_id TEXT NOT NULL REFERENCES organizations(id),
      folder_id TEXT NOT NULL REFERENCES folders(id),
      owner_id TEXT NOT NULL REFERENCES users(id),
      name TEXT NOT NULL
    )
    """.trim,
    """
    CREATE TABLE document_permissions (
      doc_id TEXT NOT NULL REFERENCES documents(id),
      user_id TEXT NOT NULL REFERENCES users(id),
      role TEXT NOT NULL
    )
    """.trim,
    """
    CREATE INDEX idx_user_groups_user ON user_groups(user_id)
    """.trim,
    """
    CREATE INDEX idx_folder_viewers_folder ON folder_viewers(folder_id)
    """.trim,
    """
    CREATE INDEX idx_document_permissions_doc ON document_permissions(doc_id)
    """.trim
  )

  def create(dataSource: DataSource): Unit = {
    withConnection(dataSource) { conn =>
      statements.foreach { sql =>
        val stmt = conn.prepareStatement(sql)
        try stmt.execute()
        finally stmt.close()
      }
    }
  }

  private def withConnection[A](dataSource: DataSource)(f: Connection => A): A = {
    val conn = dataSource.getConnection
    try f(conn)
    finally conn.close()
  }
}

private object IntegrationDataBuilder {
  private val streamSize = 4096

  def seed(dataSource: DataSource, config: IntegrationConfig, seed: Int): IntegrationDataset = {
    val rng = new Random(seed)

    val orgIds = (1 to config.orgCount).map(i => s"org-$i").toVector
    val groupIdsByOrg = orgIds.map { orgId =>
      val orgIndex = orgId.stripPrefix("org-")
      orgIndex -> (1 to config.groupsPerOrg).map(g => s"group-$orgIndex-$g").toVector
    }.toMap

    val userRows = orgIds.flatMap { orgId =>
      val orgIndex = orgId.stripPrefix("org-")
      (1 to config.usersPerOrg).map { i =>
        val id = s"user-$orgIndex-$i"
        val groups = pickSome(groupIdsByOrg(orgIndex), rng, min = 1, max = 3).toSet
        UserRow(
          id = id,
          orgId = orgId,
          name = s"User $id",
          email = s"$id@example.com",
          isAdmin = i == 1,
          groups = groups
        )
      }
    }.toVector

    val folderRows = orgIds.flatMap { orgId =>
      val orgIndex = orgId.stripPrefix("org-")
      val groupId = groupIdsByOrg(orgIndex).head
      (1 to config.foldersPerOrg).map { i =>
        val id = s"folder-$orgIndex-$i"
        val parent =
          if (i == 1) ParentRef("Bench::Group", groupId)
          else if (i <= config.folderDepth) ParentRef("Bench::Folder", s"folder-$orgIndex-${i - 1}")
          else ParentRef("Bench::Folder", s"folder-$orgIndex-1")
        FolderRow(
          id = id,
          orgId = orgId,
          parentType = parent.entityType,
          parentId = parent.entityId,
          name = s"Folder $id"
        )
      }
    }.toVector

    val folderViewerRows = folderRows.flatMap { folder =>
      val orgIndex = folder.orgId.stripPrefix("org-")
      val orgUsers = userRows.filter(_.orgId == folder.orgId).map(_.id).toVector
      val viewers = pickSome(orgUsers, rng, min = 1, max = 5)
      viewers.map(userId => FolderViewerRow(folder.id, userId))
    }

    val folderByOrg = folderRows.groupBy(_.orgId)

    val docRows = orgIds.flatMap { orgId =>
      val orgIndex = orgId.stripPrefix("org-")
      val orgUsers = userRows.filter(_.orgId == orgId).map(_.id).toVector
      val orgFolders = folderByOrg(orgId).map(_.id).toVector
      (1 to config.docsPerOrg).map { i =>
        val id = s"doc-$orgIndex-$i"
        val folderId =
          if (orgIndex == "1" && i == 1) s"folder-1-1"
          else if (orgIndex == "1" && i == 2) s"folder-1-${config.folderDepth}"
          else orgFolders(rng.nextInt(orgFolders.size))
        val owner = orgUsers(rng.nextInt(orgUsers.size))
        DocumentRow(id, orgId, folderId, owner, s"Document $id")
      }
    }.toVector

    val docPermRows = docRows.flatMap { doc =>
      val orgUsers = userRows.filter(_.orgId == doc.orgId).map(_.id).toVector
      val editors = pickSome(orgUsers, rng, min = 1, max = 3)
      val viewers = pickSome(orgUsers, rng, min = 1, max = 6)
      editors.map(userId => DocumentPermissionRow(doc.id, userId, "editor")) ++
        viewers.map(userId => DocumentPermissionRow(doc.id, userId, "viewer"))
    }

    DatabaseSeeder.insertAll(
      dataSource,
      orgIds = orgIds,
      groupIdsByOrg = groupIdsByOrg,
      userRows = userRows,
      folderRows = folderRows,
      folderViewerRows = folderViewerRows,
      docRows = docRows,
      docPermRows = docPermRows
    )

    val groupParent = groupIdsByOrg.flatMap { case (orgIndex, groups) =>
      groups.map(groupId => groupId -> ParentRef("Bench::Organization", s"org-$orgIndex"))
    }
    val folderParent = folderRows.map(row => row.id -> ParentRef(row.parentType, row.parentId)).toMap

    val docResourceById = docRows.map { doc =>
      val chain = folderParentChain(doc.folderId, folderParent, groupParent)
      val parents = (ParentRef("Bench::Folder", doc.folderId) :: chain)
        .map(p => (p.entityType, p.entityId))
      val ref = ResourceRef(
        entityType = "Bench::Document",
        entityId = Some(doc.id),
        parents = parents
      )
      doc.id -> ref
    }.toMap

    val allUsers = userRows.map(_.id).toVector
    val allDocs = docRows.map(_.id).toVector

    val hotUsers = hotSet(allUsers, config.hotFraction)
    val hotDocs = hotSet(allDocs, config.hotFraction)

    val userIdStream = weightedStream(allUsers, hotUsers, config.hotTrafficShare, streamSize, rng)
    val docIdStream = weightedStream(allDocs, hotDocs, config.hotTrafficShare, streamSize, rng)

    val principalStream = Array.tabulate(streamSize) { i =>
      val id = userIdStream(i % userIdStream.length)
      CedarPrincipal(
        uid = CedarEntityUid("Bench::User", id),
        entities = CedarEntities(userToEntity(userRows.find(_.id == id).get))
      )
    }

    val resourceStream = docIdStream.map(docResourceById)

    val requestStream = Array.tabulate(streamSize) { i =>
      CedarRequest(
        principal = principalStream(i).uid,
        action = CedarEntityUid("Bench::Action", actionForIndex(i)),
        resource = CedarEntityUid("Bench::Document", docIdStream(i)),
        context = CedarContext.empty
      )
    }

    val shallowResource = docResourceById.getOrElse("doc-1-1", docResourceById.values.head)
    val deepResource = docResourceById.getOrElse("doc-1-2", docResourceById.values.head)

    val defaultPrincipalId = userRows.find(_.orgId == "org-1").map(_.id).getOrElse(allUsers.head)
    val defaultPrincipal = CedarPrincipal(
      uid = CedarEntityUid("Bench::User", defaultPrincipalId),
      entities = CedarEntities(userToEntity(userRows.find(_.id == defaultPrincipalId).get))
    )

    IntegrationDataset(
      defaultPrincipal = defaultPrincipal,
      principalStream = principalStream,
      requestStream = requestStream,
      resourceStream = resourceStream,
      userIdStream = userIdStream,
      docIdStream = docIdStream,
      batchDocUids50 = allDocs.take(50).map(id => CedarEntityUid("Bench::Document", id)).toSet,
      shallowResource = shallowResource,
      deepResource = deepResource
    )
  }

  private def folderParentChain(
      folderId: String,
      folderParent: Map[String, ParentRef],
      groupParent: Map[String, ParentRef]
  ): List[ParentRef] = {
    var chain = List.empty[ParentRef]
    var currentType = "Bench::Folder"
    var currentId = folderId
    var continue = true
    while (continue) {
      currentType match {
        case "Bench::Folder" =>
          folderParent.get(currentId) match {
            case Some(parent) =>
              chain = chain :+ parent
              currentType = parent.entityType
              currentId = parent.entityId
            case None => continue = false
          }
        case "Bench::Group" =>
          groupParent.get(currentId) match {
            case Some(parent) =>
              chain = chain :+ parent
              currentType = parent.entityType
              currentId = parent.entityId
            case None => continue = false
          }
        case _ => continue = false
      }
    }
    chain
  }

  private def userToEntity(user: UserRow): CedarEntity = {
    CedarEntity(
      entityType = "Bench::User",
      entityId = user.id,
      attributes = Map(
        "name" -> CedarValue.string(user.name),
        "email" -> CedarValue.string(user.email),
        "groups" -> CedarValue.entitySet(user.groups, "Bench::Group"),
        "adminOf" -> CedarValue.entitySet(if (user.isAdmin) Set(user.orgId) else Set.empty, "Bench::Organization")
      )
    )
  }

  private def pickSome[A](source: Vector[A], rng: Random, min: Int, max: Int): Vector[A] = {
    if (source.isEmpty) return Vector.empty
    val count = if (max <= 0) 0 else min + rng.nextInt(math.max(1, max - min + 1))
    rng.shuffle(source).take(count)
  }

  private def hotSet(ids: Vector[String], fraction: Double): Vector[String] = {
    val count = math.max(1, (ids.size * fraction).toInt)
    ids.take(count)
  }

  private def weightedStream(
      all: Vector[String],
      hot: Vector[String],
      hotShare: Double,
      size: Int,
      rng: Random
  ): Array[String] = {
    val cold = all.drop(hot.size)
    Array.tabulate(size) { _ =>
      val useHot = cold.isEmpty || rng.nextDouble() < hotShare
      if (useHot) hot(rng.nextInt(hot.size))
      else cold(rng.nextInt(cold.size))
    }
  }

  private def actionForIndex(i: Int): String = {
    val mod = i % 100
    if (mod < 70) "read"
    else if (mod < 90) "write"
    else if (mod < 98) "delete"
    else "admin"
  }
}

private object DatabaseSeeder {
  def insertAll(
      dataSource: DataSource,
      orgIds: Vector[String],
      groupIdsByOrg: Map[String, Vector[String]],
      userRows: Vector[UserRow],
      folderRows: Vector[FolderRow],
      folderViewerRows: Vector[FolderViewerRow],
      docRows: Vector[DocumentRow],
      docPermRows: Vector[DocumentPermissionRow]
  ): Unit = {
    withConnection(dataSource) { conn =>
      conn.setAutoCommit(false)
      try {
        batchInsert(conn, "INSERT INTO organizations (id, name) VALUES (?, ?)", orgIds) { (ps, id) =>
          ps.setString(1, id)
          ps.setString(2, s"Organization $id")
        }

        val groupRows = groupIdsByOrg.toVector.flatMap { case (orgIndex, groups) =>
          groups.map(id => GroupRow(id, s"org-$orgIndex"))
        }
        batchInsert(conn, "INSERT INTO groups (id, org_id) VALUES (?, ?)", groupRows) { (ps, row) =>
          ps.setString(1, row.id)
          ps.setString(2, row.orgId)
        }

        batchInsert(conn, "INSERT INTO users (id, org_id, name, email, is_admin) VALUES (?, ?, ?, ?, ?)", userRows) {
          (ps, row) =>
            ps.setString(1, row.id)
            ps.setString(2, row.orgId)
            ps.setString(3, row.name)
            ps.setString(4, row.email)
            ps.setBoolean(5, row.isAdmin)
        }

        val userGroupRows = userRows.flatMap { row =>
          row.groups.toVector.map(groupId => UserGroupRow(row.id, groupId))
        }
        batchInsert(conn, "INSERT INTO user_groups (user_id, group_id) VALUES (?, ?)", userGroupRows) { (ps, row) =>
          ps.setString(1, row.userId)
          ps.setString(2, row.groupId)
        }

        batchInsert(
          conn,
          "INSERT INTO folders (id, org_id, parent_type, parent_id, name) VALUES (?, ?, ?, ?, ?)",
          folderRows
        ) { (ps, row) =>
          ps.setString(1, row.id)
          ps.setString(2, row.orgId)
          ps.setString(3, row.parentType)
          ps.setString(4, row.parentId)
          ps.setString(5, row.name)
        }

        batchInsert(conn, "INSERT INTO folder_viewers (folder_id, user_id) VALUES (?, ?)", folderViewerRows) {
          (ps, row) =>
            ps.setString(1, row.folderId)
            ps.setString(2, row.userId)
        }

        batchInsert(
          conn,
          "INSERT INTO documents (id, org_id, folder_id, owner_id, name) VALUES (?, ?, ?, ?, ?)",
          docRows
        ) { (ps, row) =>
          ps.setString(1, row.id)
          ps.setString(2, row.orgId)
          ps.setString(3, row.folderId)
          ps.setString(4, row.ownerId)
          ps.setString(5, row.name)
        }

        batchInsert(conn, "INSERT INTO document_permissions (doc_id, user_id, role) VALUES (?, ?, ?)", docPermRows) {
          (ps, row) =>
            ps.setString(1, row.docId)
            ps.setString(2, row.userId)
            ps.setString(3, row.role)
        }

        conn.commit()
      } finally {
        conn.setAutoCommit(true)
      }
    }
  }

  private def batchInsert[A](
      conn: Connection,
      sql: String,
      rows: Seq[A],
      batchSize: Int = 500
  )(bind: (PreparedStatement, A) => Unit): Unit = {
    if (rows.isEmpty) return
    val ps = conn.prepareStatement(sql)
    try {
      var count = 0
      rows.foreach { row =>
        bind(ps, row)
        ps.addBatch()
        count += 1
        if (count % batchSize == 0) ps.executeBatch()
      }
      ps.executeBatch()
    } finally {
      ps.close()
    }
  }

  private def withConnection[A](dataSource: DataSource)(f: Connection => A): A = {
    val conn = dataSource.getConnection
    try f(conn)
    finally conn.close()
  }
}

private object JdbcEntityStore {
  def build(dataSource: DataSource)(using ec: ExecutionContext): EntityStore[Future] = {
    EntityStore
      .builder[Future]()
      .register(UserFetcher(dataSource))(using entityTypeFor("Bench::User"), Bijection.identity[String])
      .register(GroupFetcher(dataSource))(using entityTypeFor("Bench::Group"), Bijection.identity[String])
      .register(OrganizationFetcher(dataSource))(using entityTypeFor("Bench::Organization"), Bijection.identity[String])
      .register(FolderFetcher(dataSource))(using entityTypeFor("Bench::Folder"), Bijection.identity[String])
      .register(DocumentFetcher(dataSource))(using entityTypeFor("Bench::Document"), Bijection.identity[String])
      .build()
  }

  private def entityTypeFor(entityTypeName: String): CedarEntityType.Aux[CedarEntity, String] =
    CedarEntityType.instance(
      entityTypeName = entityTypeName,
      toEntity = identity,
      parentIds = e => e.parents.toList.map(p => (p.entityType, p.entityId))
    )
}

private final case class ParentRef(entityType: String, entityId: String)
private final case class UserRow(
    id: String,
    orgId: String,
    name: String,
    email: String,
    isAdmin: Boolean,
    groups: Set[String]
)
private final case class GroupRow(id: String, orgId: String)
private final case class UserGroupRow(userId: String, groupId: String)
private final case class FolderRow(id: String, orgId: String, parentType: String, parentId: String, name: String)
private final case class FolderViewerRow(folderId: String, userId: String)
private final case class DocumentRow(id: String, orgId: String, folderId: String, ownerId: String, name: String)
private final case class DocumentPermissionRow(docId: String, userId: String, role: String)

private object UserFetcher {
  def apply(dataSource: DataSource)(using ec: ExecutionContext): EntityFetcher[Future, CedarEntity, String] =
    new EntityFetcher[Future, CedarEntity, String] {
      override def fetch(id: String): Future[Option[CedarEntity]] =
        fetchBatch(Set(id)).map(_.get(id))

      override def fetchBatch(ids: Set[String])(using F: Applicative[Future]): Future[Map[String, CedarEntity]] =
        if (ids.isEmpty) Future.successful(Map.empty)
        else
          Future {
            blocking {
              val idList = ids.toVector
              JdbcQueries.withConnection(dataSource) { conn =>
                val users = JdbcQueries.selectUsers(conn, idList)
                val groupsByUser = JdbcQueries.selectUserGroups(conn, idList)
                users.map { case (id, row) =>
                  val groups = groupsByUser.getOrElse(id, Set.empty)
                  val entity = CedarEntity(
                    entityType = "Bench::User",
                    entityId = id,
                    attributes = Map(
                      "name" -> CedarValue.string(row.name),
                      "email" -> CedarValue.string(row.email),
                      "groups" -> CedarValue.entitySet(groups, "Bench::Group"),
                      "adminOf" -> CedarValue
                        .entitySet(if (row.isAdmin) Set(row.orgId) else Set.empty, "Bench::Organization")
                    )
                  )
                  id -> entity
                }
              }
            }
          }
    }
}

private object GroupFetcher {
  def apply(dataSource: DataSource)(using ec: ExecutionContext): EntityFetcher[Future, CedarEntity, String] =
    new EntityFetcher[Future, CedarEntity, String] {
      override def fetch(id: String): Future[Option[CedarEntity]] =
        fetchBatch(Set(id)).map(_.get(id))

      override def fetchBatch(ids: Set[String])(using F: Applicative[Future]): Future[Map[String, CedarEntity]] =
        if (ids.isEmpty) Future.successful(Map.empty)
        else
          Future {
            blocking {
              val idList = ids.toVector
              JdbcQueries.withConnection(dataSource) { conn =>
                val groups = JdbcQueries.selectGroups(conn, idList)
                groups.map { case (id, row) =>
                  val entity = CedarEntity(
                    entityType = "Bench::Group",
                    entityId = id,
                    parents = Set(CedarEntityUid("Bench::Organization", row.orgId)),
                    attributes = Map("name" -> CedarValue.string(s"Group $id"))
                  )
                  id -> entity
                }
              }
            }
          }
    }
}

private object OrganizationFetcher {
  def apply(dataSource: DataSource)(using ec: ExecutionContext): EntityFetcher[Future, CedarEntity, String] =
    new EntityFetcher[Future, CedarEntity, String] {
      override def fetch(id: String): Future[Option[CedarEntity]] =
        fetchBatch(Set(id)).map(_.get(id))

      override def fetchBatch(ids: Set[String])(using F: Applicative[Future]): Future[Map[String, CedarEntity]] =
        if (ids.isEmpty) Future.successful(Map.empty)
        else
          Future {
            blocking {
              val idList = ids.toVector
              JdbcQueries.withConnection(dataSource) { conn =>
                val orgs = JdbcQueries.selectOrganizations(conn, idList)
                orgs.map { case (id, name) =>
                  val entity = CedarEntity(
                    entityType = "Bench::Organization",
                    entityId = id,
                    attributes = Map("name" -> CedarValue.string(name))
                  )
                  id -> entity
                }
              }
            }
          }
    }
}

private object FolderFetcher {
  def apply(dataSource: DataSource)(using ec: ExecutionContext): EntityFetcher[Future, CedarEntity, String] =
    new EntityFetcher[Future, CedarEntity, String] {
      override def fetch(id: String): Future[Option[CedarEntity]] =
        fetchBatch(Set(id)).map(_.get(id))

      override def fetchBatch(ids: Set[String])(using F: Applicative[Future]): Future[Map[String, CedarEntity]] =
        if (ids.isEmpty) Future.successful(Map.empty)
        else
          Future {
            blocking {
              val idList = ids.toVector
              JdbcQueries.withConnection(dataSource) { conn =>
                val folders = JdbcQueries.selectFolders(conn, idList)
                val viewersByFolder = JdbcQueries.selectFolderViewers(conn, idList)
                folders.map { case (id, row) =>
                  val viewers = viewersByFolder.getOrElse(id, Set.empty)
                  val entity = CedarEntity(
                    entityType = "Bench::Folder",
                    entityId = id,
                    parents = Set(CedarEntityUid(row.parentType, row.parentId)),
                    attributes = Map(
                      "name" -> CedarValue.string(row.name),
                      "viewers" -> CedarValue.entitySet(viewers, "Bench::User")
                    )
                  )
                  id -> entity
                }
              }
            }
          }
    }
}

private object DocumentFetcher {
  def apply(dataSource: DataSource)(using ec: ExecutionContext): EntityFetcher[Future, CedarEntity, String] =
    new EntityFetcher[Future, CedarEntity, String] {
      override def fetch(id: String): Future[Option[CedarEntity]] =
        fetchBatch(Set(id)).map(_.get(id))

      override def fetchBatch(ids: Set[String])(using F: Applicative[Future]): Future[Map[String, CedarEntity]] =
        if (ids.isEmpty) Future.successful(Map.empty)
        else
          Future {
            blocking {
              val idList = ids.toVector
              JdbcQueries.withConnection(dataSource) { conn =>
                val docs = JdbcQueries.selectDocuments(conn, idList)
                val perms = JdbcQueries.selectDocumentPermissions(conn, idList)
                docs.map { case (id, row) =>
                  val editors = perms.getOrElse((id, "editor"), Set.empty)
                  val viewers = perms.getOrElse((id, "viewer"), Set.empty)
                  val entity = CedarEntity(
                    entityType = "Bench::Document",
                    entityId = id,
                    parents = Set(CedarEntityUid("Bench::Folder", row.folderId)),
                    attributes = Map(
                      "owner" -> CedarValue.entity("Bench::User", row.ownerId),
                      "editors" -> CedarValue.entitySet(editors, "Bench::User"),
                      "viewers" -> CedarValue.entitySet(viewers, "Bench::User"),
                      "folder" -> CedarValue.entity("Bench::Folder", row.folderId),
                      "name" -> CedarValue.string(row.name)
                    )
                  )
                  id -> entity
                }
              }
            }
          }
    }
}

private object JdbcQueries {
  def withConnection[A](dataSource: DataSource)(f: Connection => A): A = {
    val conn = dataSource.getConnection
    try f(conn)
    finally conn.close()
  }

  def selectUsers(conn: Connection, ids: Vector[String]): Map[String, UserRow] = {
    val sql = s"SELECT id, org_id, name, email, is_admin FROM users WHERE id IN (${placeholders(ids.size)})"
    query(conn, sql, ids) { rs =>
      val id = rs.getString("id")
      id -> UserRow(
        id = id,
        orgId = rs.getString("org_id"),
        name = rs.getString("name"),
        email = rs.getString("email"),
        isAdmin = rs.getBoolean("is_admin"),
        groups = Set.empty
      )
    }.toMap
  }

  def selectUserGroups(conn: Connection, userIds: Vector[String]): Map[String, Set[String]] = {
    val sql = s"SELECT user_id, group_id FROM user_groups WHERE user_id IN (${placeholders(userIds.size)})"
    val rows = query(conn, sql, userIds) { rs =>
      rs.getString("user_id") -> rs.getString("group_id")
    }
    rows.groupBy(_._1).view.mapValues(_.map(_._2).toSet).toMap
  }

  def selectGroups(conn: Connection, ids: Vector[String]): Map[String, GroupRow] = {
    val sql = s"SELECT id, org_id FROM groups WHERE id IN (${placeholders(ids.size)})"
    query(conn, sql, ids) { rs =>
      val id = rs.getString("id")
      id -> GroupRow(id, rs.getString("org_id"))
    }.toMap
  }

  def selectOrganizations(conn: Connection, ids: Vector[String]): Map[String, String] = {
    val sql = s"SELECT id, name FROM organizations WHERE id IN (${placeholders(ids.size)})"
    query(conn, sql, ids) { rs =>
      rs.getString("id") -> rs.getString("name")
    }.toMap
  }

  def selectFolders(conn: Connection, ids: Vector[String]): Map[String, FolderRow] = {
    val sql = s"SELECT id, org_id, parent_type, parent_id, name FROM folders WHERE id IN (${placeholders(ids.size)})"
    query(conn, sql, ids) { rs =>
      val id = rs.getString("id")
      id -> FolderRow(
        id = id,
        orgId = rs.getString("org_id"),
        parentType = rs.getString("parent_type"),
        parentId = rs.getString("parent_id"),
        name = rs.getString("name")
      )
    }.toMap
  }

  def selectFolderViewers(conn: Connection, ids: Vector[String]): Map[String, Set[String]] = {
    val sql = s"SELECT folder_id, user_id FROM folder_viewers WHERE folder_id IN (${placeholders(ids.size)})"
    val rows = query(conn, sql, ids) { rs =>
      rs.getString("folder_id") -> rs.getString("user_id")
    }
    rows.groupBy(_._1).view.mapValues(_.map(_._2).toSet).toMap
  }

  def selectDocuments(conn: Connection, ids: Vector[String]): Map[String, DocumentRow] = {
    val sql = s"SELECT id, org_id, folder_id, owner_id, name FROM documents WHERE id IN (${placeholders(ids.size)})"
    query(conn, sql, ids) { rs =>
      val id = rs.getString("id")
      id -> DocumentRow(
        id = id,
        orgId = rs.getString("org_id"),
        folderId = rs.getString("folder_id"),
        ownerId = rs.getString("owner_id"),
        name = rs.getString("name")
      )
    }.toMap
  }

  def selectDocumentPermissions(conn: Connection, ids: Vector[String]): Map[(String, String), Set[String]] = {
    val sql = s"SELECT doc_id, user_id, role FROM document_permissions WHERE doc_id IN (${placeholders(ids.size)})"
    val rows = query(conn, sql, ids) { rs =>
      (rs.getString("doc_id"), rs.getString("role")) -> rs.getString("user_id")
    }
    rows.groupBy(_._1).view.mapValues(_.map(_._2).toSet).toMap
  }

  private def query[A](conn: Connection, sql: String, ids: Vector[String])(mapper: ResultSet => A): Vector[A] = {
    if (ids.isEmpty) return Vector.empty
    val ps = conn.prepareStatement(sql)
    try {
      ids.zipWithIndex.foreach { case (id, idx) => ps.setString(idx + 1, id) }
      val rs = ps.executeQuery()
      val buffer = Vector.newBuilder[A]
      while (rs.next()) buffer += mapper(rs)
      buffer.result()
    } finally {
      ps.close()
    }
  }

  private def placeholders(size: Int): String =
    if (size <= 0) "" else List.fill(size)("?").mkString(", ")
}
