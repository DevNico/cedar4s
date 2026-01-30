package example

import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

// Import generated Cedar types
import example.saas.cedar.SaaS
import example.saas.cedar.EntityIds.{OrganizationId, WorkspaceId, ProjectId, EnvironmentId, SecretId, UserId}

// Import cedar4s types
import cedar4s.auth.*
import cedar4s.client.{CedarEngine, CedarRuntime}
import cedar4s.entities.*
import cedar4s.schema.CedarEntityUid
// Import Future Monad instance for EntityStore.builder
import cedar4s.capability.instances.futureMonadError
// Import sync instance for Future
import cedar4s.capability.instances.futureSync

/** Multi-Tenant SaaS Example
  *
  * Demonstrates cedar4s with REAL Cedar policy evaluation:
  *   1. EntityFetchers load entities with permission sets from data store
  *   2. CedarEngine evaluates Cedar policies (.cedar files)
  *   3. CedarSession bridges the DSL to the Cedar engine
  *
  * Features demonstrated:
  *   - Deep resource hierarchies (Org -> Workspace -> Project -> Environment -> Secret)
  *   - Tenant isolation via permission sets
  *   - Role-based access within tenants
  *   - Protected environment restrictions
  */
object MultiTenantExample {

  // ============================================================================
  // Domain Models (mapping to generated Cedar entity types)
  // ============================================================================

  // Note: We use simplified models here for the example
  // In a real app, you'd map your domain models to the generated *Data classes
  case class User(id: String, email: String, name: String)
  case class ServiceAccount(id: String, orgId: String, name: String, description: String, scopes: Set[String])

  case class OrgEntity(
      id: String,
      name: String,
      plan: String,
      maxWorkspaces: Long,
      maxMembers: Long,
      owners: Set[String],
      admins: Set[String],
      members: Set[String],
      billingUsers: Set[String]
  )

  case class WorkspaceEntity(
      id: String,
      orgId: String,
      name: String,
      description: String,
      admins: Set[String],
      members: Set[String],
      viewers: Set[String]
  )

  case class ProjectEntity(
      id: String,
      workspaceId: String,
      name: String,
      visibility: String,
      archived: Boolean,
      admins: Set[String],
      maintainers: Set[String],
      developers: Set[String],
      viewers: Set[String]
  )

  case class EnvironmentEntity(
      id: String,
      projectId: String,
      name: String,
      isProtected: Boolean,
      admins: Set[String],
      members: Set[String],
      viewers: Set[String]
  )

  case class SecretEntity(
      id: String,
      envId: String,
      name: String,
      version: Long,
      admins: Set[String],
      members: Set[String],
      viewers: Set[String]
  )

  // ============================================================================
  // In-Memory Data Store
  // ============================================================================

  object DataStore {
    val users = Map(
      "alice" -> User("alice", "alice@acme.com", "Alice"),
      "bob" -> User("bob", "bob@acme.com", "Bob"),
      "carol" -> User("carol", "carol@acme.com", "Carol"),
      "eve" -> User("eve", "eve@evil.com", "Eve") // Different tenant!
    )

    val serviceAccounts = Map(
      "ci-bot" -> ServiceAccount(
        "ci-bot",
        "acme-org",
        "CI Bot",
        "Continuous Integration Bot",
        Set("Project::view", "Deployment::create")
      )
    )

    val orgs = Map(
      "acme-org" -> OrgEntity(
        id = "acme-org",
        name = "Acme Corp",
        plan = "enterprise",
        maxWorkspaces = 10,
        maxMembers = 100,
        owners = Set("alice"),
        admins = Set("alice", "bob"),
        members = Set("alice", "bob", "carol"),
        billingUsers = Set("alice")
      ),
      "evil-org" -> OrgEntity(
        id = "evil-org",
        name = "Evil Corp",
        plan = "free",
        maxWorkspaces = 3,
        maxMembers = 10,
        owners = Set("eve"),
        admins = Set("eve"),
        members = Set("eve"),
        billingUsers = Set("eve")
      )
    )

    val workspaces = Map(
      "acme-dev" -> WorkspaceEntity(
        id = "acme-dev",
        orgId = "acme-org",
        name = "Development",
        description = "Development workspace",
        admins = Set("alice", "bob"),
        members = Set("alice", "bob", "carol"),
        viewers = Set("carol")
      ),
      "acme-prod" -> WorkspaceEntity(
        id = "acme-prod",
        orgId = "acme-org",
        name = "Production",
        description = "Production workspace",
        admins = Set("alice"),
        members = Set("alice", "bob"),
        viewers = Set("carol")
      )
    )

    val projects = Map(
      "api-service" -> ProjectEntity(
        id = "api-service",
        workspaceId = "acme-dev",
        name = "API Service",
        visibility = "private",
        archived = false,
        admins = Set("alice"),
        maintainers = Set("bob"),
        developers = Set("carol"),
        viewers = Set("carol")
      ),
      "old-project" -> ProjectEntity(
        id = "old-project",
        workspaceId = "acme-dev",
        name = "Old Project",
        visibility = "private",
        archived = true,
        admins = Set("alice"),
        maintainers = Set("bob"),
        developers = Set.empty,
        viewers = Set("carol")
      )
    )

    val environments = Map(
      "api-dev" -> EnvironmentEntity(
        id = "api-dev",
        projectId = "api-service",
        name = "development",
        isProtected = false,
        admins = Set("alice"),
        members = Set("bob", "carol"),
        viewers = Set("carol")
      ),
      "api-prod" -> EnvironmentEntity(
        id = "api-prod",
        projectId = "api-service",
        name = "production",
        isProtected = true,
        admins = Set("alice"),
        members = Set("bob"),
        viewers = Set("carol")
      )
    )

    val secrets = Map(
      "db-password" -> SecretEntity(
        id = "db-password",
        envId = "api-prod",
        name = "DATABASE_URL",
        version = 1,
        admins = Set("alice"),
        members = Set("bob"),
        viewers = Set("carol")
      )
    )
  }

  // ============================================================================
  // Entity Fetchers - Load entities and convert to generated Cedar entity classes
  // ============================================================================

  /** Fetcher for Organization entities.
    */
  class OrganizationFetcher extends EntityFetcher[Future, SaaS.Entity.Organization, OrganizationId] {
    def fetch(id: OrganizationId): Future[Option[SaaS.Entity.Organization]] =
      Future.successful(DataStore.orgs.get(id.value).map { org =>
        SaaS.Entity.Organization(
          id = OrganizationId(org.id),
          name = org.name,
          plan = org.plan,
          maxworkspaces = org.maxWorkspaces,
          maxmembers = org.maxMembers,
          owners = org.owners,
          admins = org.admins,
          members = org.members,
          billingusers = org.billingUsers
        )
      })
  }

  /** Fetcher for Workspace entities.
    */
  class WorkspaceFetcher extends EntityFetcher[Future, SaaS.Entity.Workspace, WorkspaceId] {
    def fetch(id: WorkspaceId): Future[Option[SaaS.Entity.Workspace]] =
      Future.successful(DataStore.workspaces.get(id.value).map { ws =>
        SaaS.Entity.Workspace(
          id = WorkspaceId(ws.id),
          organizationId = OrganizationId(ws.orgId),
          name = ws.name,
          description = ws.description,
          admins = ws.admins,
          members = ws.members,
          viewers = ws.viewers
        )
      })
  }

  /** Fetcher for Project entities.
    */
  class ProjectFetcher extends EntityFetcher[Future, SaaS.Entity.Project, ProjectId] {
    def fetch(id: ProjectId): Future[Option[SaaS.Entity.Project]] =
      Future.successful(DataStore.projects.get(id.value).map { proj =>
        SaaS.Entity.Project(
          id = ProjectId(proj.id),
          workspaceId = WorkspaceId(proj.workspaceId),
          name = proj.name,
          visibility = proj.visibility,
          archived = proj.archived,
          admins = proj.admins,
          maintainers = proj.maintainers,
          developers = proj.developers,
          viewers = proj.viewers
        )
      })
  }

  /** Fetcher for Environment entities.
    */
  class EnvironmentFetcher extends EntityFetcher[Future, SaaS.Entity.Environment, EnvironmentId] {
    def fetch(id: EnvironmentId): Future[Option[SaaS.Entity.Environment]] =
      Future.successful(DataStore.environments.get(id.value).map { env =>
        SaaS.Entity.Environment(
          id = EnvironmentId(env.id),
          projectId = ProjectId(env.projectId),
          name = env.name,
          isprotected = env.isProtected,
          admins = env.admins,
          members = env.members,
          viewers = env.viewers
        )
      })
  }

  /** Fetcher for Secret entities.
    */
  class SecretFetcher extends EntityFetcher[Future, SaaS.Entity.Secret, SecretId] {
    def fetch(id: SecretId): Future[Option[SaaS.Entity.Secret]] =
      Future.successful(DataStore.secrets.get(id.value).map { secret =>
        SaaS.Entity.Secret(
          id = SecretId(secret.id),
          environmentId = EnvironmentId(secret.envId),
          name = secret.name,
          version = secret.version,
          admins = secret.admins,
          members = secret.members,
          viewers = secret.viewers
        )
      })
  }

  /** Fetcher for User entities (principals).
    */
  class UserFetcher extends EntityFetcher[Future, SaaS.Entity.User, UserId] {
    def fetch(id: UserId): Future[Option[SaaS.Entity.User]] =
      Future.successful(DataStore.users.get(id.value).map { user =>
        SaaS.Entity.User(
          id = UserId(user.id),
          email = user.email,
          name = user.name,
          emailverified = true
        )
      })
  }

  // ============================================================================
  // CedarSession using CedarEngine for REAL policy evaluation
  // ============================================================================

  def resolvePrincipal(principal: Principal): Future[Option[SaaS.Entity.User]] = {
    val userId = principal match {
      case SaaS.Principal.User(id: UserId) => id.value
      case _                               => principal.entityId
    }

    Future.successful(
      DataStore.users.get(userId).map { user =>
        SaaS.Entity.User(
          id = UserId(user.id),
          email = user.email,
          name = user.name,
          emailverified = true
        )
      }
    )
  }

  // ============================================================================
  // Setup
  // ============================================================================

  // Create EntityStore with all fetchers using the new effect-polymorphic API
  val entityStore: EntityStore[Future] = EntityStore
    .builder[Future]()
    .register[SaaS.Entity.Organization, OrganizationId](new OrganizationFetcher())
    .register[SaaS.Entity.Workspace, WorkspaceId](new WorkspaceFetcher())
    .register[SaaS.Entity.Project, ProjectId](new ProjectFetcher())
    .register[SaaS.Entity.Environment, EnvironmentId](new EnvironmentFetcher())
    .register[SaaS.Entity.Secret, SecretId](new SecretFetcher())
    .register[SaaS.Entity.User, UserId](new UserFetcher())
    .build()

  // Load Cedar engine with policies from resources
  val cedarEngine: CedarEngine[Future] = CedarEngine.fromResources(
    policiesPath = "policies",
    policyFiles = Seq("saas.cedar")
  )

  private val cedarRuntime: CedarRuntime[Future, SaaS.Entity.User] =
    CedarRuntime[Future, SaaS.Entity.User](cedarEngine, entityStore, CedarRuntime.resolverFrom(resolvePrincipal))

  // Helper to create CedarSession for a user
  def sessionFor(user: User): CedarSession[Future] =
    cedarRuntime.session(SaaS.Principal.User(UserId(user.id)))

  // FlatMap instance for Future
  given futureFlatMap: FlatMap[Future] = FlatMap.futureInstance

  // ============================================================================
  // Example Usage
  // ============================================================================

  def main(args: Array[String]): Unit = {
    println("=== Multi-Tenant SaaS Example with Cedar Policies ===\n")
    println("Authorization decisions are made by Cedar policies in saas.cedar,")
    println("NOT by Scala code. EntityFetchers load entities with permission sets.\n")

    val alice = DataStore.users("alice") // Org owner, workspace/project admin
    val bob = DataStore.users("bob") // Org admin, project maintainer
    val carol = DataStore.users("carol") // Org member, project developer
    val eve = DataStore.users("eve") // Different tenant (Evil Corp)!

    // -------------------------------------------------------------------------
    // 1. Tenant Isolation
    // -------------------------------------------------------------------------
    println("1. Tenant Isolation (Cross-Tenant Access Prevention)")
    println("-" * 60)

    // Alice (ACME member) accessing ACME resources
    {
      given CedarSession[Future] = sessionFor(alice)
      val result = Await.result(SaaS.Organization.View.on(OrganizationId("acme-org")).run, 5.seconds)
      println(s"   Alice (ACME) -> SaaS.Organization.View(acme-org): ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    // Eve (Evil Corp) trying to access ACME resources - DENIED (not in permission set)
    {
      given CedarSession[Future] = sessionFor(eve)
      val result = Await.result(SaaS.Organization.View.on(OrganizationId("acme-org")).run, 5.seconds)
      println(s"   Eve (Evil)   -> SaaS.Organization.View(acme-org): ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    // -------------------------------------------------------------------------
    // 2. Role-Based Access within Tenant
    // -------------------------------------------------------------------------
    println("\n2. Role-Based Access within Tenant")
    println("-" * 60)

    // Alice (owner) can delete org
    {
      given CedarSession[Future] = sessionFor(alice)
      val result = Await.result(SaaS.Organization.Delete.on(OrganizationId("acme-org")).run, 5.seconds)
      println(s"   Alice (owner) -> SaaS.Organization.Delete: ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    // Bob (admin) cannot delete org (only owners can)
    {
      given CedarSession[Future] = sessionFor(bob)
      val result = Await.result(SaaS.Organization.Delete.on(OrganizationId("acme-org")).run, 5.seconds)
      println(s"   Bob (admin)   -> SaaS.Organization.Delete: ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    // Carol (member) can view but not manage members
    {
      given CedarSession[Future] = sessionFor(carol)
      val viewResult = Await.result(SaaS.Organization.View.on(OrganizationId("acme-org")).run, 5.seconds)
      val manageResult = Await.result(SaaS.Organization.ManageMembers.on(OrganizationId("acme-org")).run, 5.seconds)
      println(s"   Carol (member) -> View: ${if viewResult.isRight then "ALLOWED" else "DENIED"}, ManageMembers: ${
          if manageResult.isRight then "ALLOWED" else "DENIED"
        }")
    }

    // -------------------------------------------------------------------------
    // 3. Workspace Permissions
    // -------------------------------------------------------------------------
    println("\n3. Workspace Permissions")
    println("-" * 60)

    // Bob (workspace admin) can delete workspace
    {
      given CedarSession[Future] = sessionFor(bob)
      val result = Await.result(SaaS.Workspace.Delete.on(WorkspaceId("acme-dev")).run, 5.seconds)
      println(s"   Bob (ws admin) -> SaaS.Workspace.Delete: ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    // Carol (workspace viewer) cannot delete
    {
      given CedarSession[Future] = sessionFor(carol)
      val viewResult = Await.result(SaaS.Workspace.View.on(WorkspaceId("acme-dev")).run, 5.seconds)
      val deleteResult = Await.result(SaaS.Workspace.Delete.on(WorkspaceId("acme-dev")).run, 5.seconds)
      println(s"   Carol (ws viewer) -> View: ${if viewResult.isRight then "ALLOWED" else "DENIED"}, Delete: ${
          if deleteResult.isRight then "ALLOWED" else "DENIED"
        }")
    }

    // -------------------------------------------------------------------------
    // 4. Project Permissions & Archived Projects
    // -------------------------------------------------------------------------
    println("\n4. Project Permissions & Archived Projects")
    println("-" * 60)

    // Active project: maintainer can update
    {
      given CedarSession[Future] = sessionFor(bob)
      val result = Await.result(SaaS.Project.Update.on(ProjectId("api-service")).run, 5.seconds)
      println(s"   Bob -> SaaS.Project.Update(api-service, active): ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    // Archived project: even admin cannot update (forbidden by policy)
    {
      given CedarSession[Future] = sessionFor(alice)
      val viewResult = Await.result(SaaS.Project.View.on(ProjectId("old-project")).run, 5.seconds)
      val updateResult = Await.result(SaaS.Project.Update.on(ProjectId("old-project")).run, 5.seconds)
      println(s"   Alice -> old-project (archived): View: ${
          if viewResult.isRight then "ALLOWED" else "DENIED"
        }, Update: ${if updateResult.isRight then "ALLOWED" else "DENIED"}")
    }

    // -------------------------------------------------------------------------
    // 5. Protected Environment Restrictions
    // -------------------------------------------------------------------------
    println("\n5. Protected Environment Restrictions")
    println("-" * 60)

    // Unprotected environment: member can deploy
    {
      given CedarSession[Future] = sessionFor(carol)
      val result = Await.result(SaaS.Environment.Update.on(EnvironmentId("api-dev")).run, 5.seconds)
      println(
        s"   Carol -> SaaS.Environment.Update(api-dev, unprotected): ${if result.isRight then "ALLOWED" else "DENIED"}"
      )
    }

    // Protected environment: only admin can update
    {
      given CedarSession[Future] = sessionFor(bob)
      val result = Await.result(SaaS.Environment.Update.on(EnvironmentId("api-prod")).run, 5.seconds)
      println(s"   Bob (member) -> SaaS.Environment.Update(api-prod, protected): ${
          if result.isRight then "ALLOWED" else "DENIED"
        }")
    }

    {
      given CedarSession[Future] = sessionFor(alice)
      val result = Await.result(SaaS.Environment.Update.on(EnvironmentId("api-prod")).run, 5.seconds)
      println(s"   Alice (admin) -> SaaS.Environment.Update(api-prod, protected): ${
          if result.isRight then "ALLOWED" else "DENIED"
        }")
    }

    // -------------------------------------------------------------------------
    // 6. Secret Access Levels
    // -------------------------------------------------------------------------
    println("\n6. Secret Access Levels")
    println("-" * 60)

    // Admin can view secret value
    {
      given CedarSession[Future] = sessionFor(alice)
      val viewResult = Await.result(SaaS.Secret.View.on(SecretId("db-password")).run, 5.seconds)
      val viewValueResult = Await.result(SaaS.Secret.ViewValue.on(SecretId("db-password")).run, 5.seconds)
      println(s"   Alice (admin) -> Secret View: ${if viewResult.isRight then "ALLOWED" else "DENIED"}, ViewValue: ${
          if viewValueResult.isRight then "ALLOWED" else "DENIED"
        }")
    }

    // Viewer can only view metadata, not the actual value
    {
      given CedarSession[Future] = sessionFor(carol)
      val viewResult = Await.result(SaaS.Secret.View.on(SecretId("db-password")).run, 5.seconds)
      val viewValueResult = Await.result(SaaS.Secret.ViewValue.on(SecretId("db-password")).run, 5.seconds)
      println(s"   Carol (viewer) -> Secret View: ${if viewResult.isRight then "ALLOWED" else "DENIED"}, ViewValue: ${
          if viewValueResult.isRight then "ALLOWED" else "DENIED"
        }")
    }

    // -------------------------------------------------------------------------
    // 7. Deep Hierarchy with Deferred Resolution
    // -------------------------------------------------------------------------
    println("\n7. Deep Hierarchy with Deferred Resolution")
    println("-" * 60)

    // The .on(id) pattern is now the only syntax - it resolves the hierarchy via EntityStore
    println("   The .on(id) syntax resolves parent hierarchy automatically via EntityStore")

    // Deferred syntax (only secret ID needed)
    {
      given CedarSession[Future] = sessionFor(alice)
      val deferred = SaaS.Secret.View.on(SecretId("db-password"))
      val result = Await.result(deferred.run, 5.seconds)
      println(s"   Syntax: SaaS.Secret.View.on(SecretId(\"db-password\"))")
      println(s"   Result: ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    println("\n=== Done ===")
  }
}
