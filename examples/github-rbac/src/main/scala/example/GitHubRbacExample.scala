package example

import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

// Import generated Cedar types
import example.github.cedar.GitHub
import example.github.cedar.EntityIds.{OrganizationId, RepositoryId, BranchId, PullRequestId, UserId}

// Import cedar4s types
import cedar4s.auth.*
import cedar4s.client.{CedarEngine, CedarRuntime}
import cedar4s.entities.*
// Import Future Monad instance for EntityStore.builder
import cedar4s.capability.instances.futureMonadError
import cedar4s.capability.instances.futureSync

/** GitHub-style RBAC Example
  *
  * Demonstrates cedar4s with REAL Cedar policy evaluation:
  *   1. EntityFetchers load entities with attributes from data store
  *   2. CedarEngine evaluates Cedar policies (.cedar files)
  *   3. CedarSession bridges the DSL to the Cedar engine
  *
  * Features demonstrated:
  *   - Team-based permissions via entity attributes
  *   - Repository visibility (public repos allow anonymous access)
  *   - Protected branch restrictions
  *   - PR author permissions
  */
object GitHubRbacExample {

  // ============================================================================
  // Domain Models (mapping to generated Cedar entity types)
  // ============================================================================

  case class User(id: String, login: String, orgs: Set[String], teams: Map[String, String])
  case class TeamEntity(id: String, orgId: String, name: String, slug: String, privacy: String, permission: String)

  case class OrgEntity(
      id: String,
      login: String,
      name: String,
      plan: String,
      defaultRepoPermission: String,
      members: Set[String],
      admins: Set[String],
      membersCanCreateRepos: Boolean
  )
  case class RepoEntity(
      id: String,
      orgId: String,
      name: String,
      visibility: String,
      archived: Boolean,
      defaultBranch: String,
      allowForking: Boolean,
      admins: Set[String],
      writers: Set[String],
      readers: Set[String]
  )
  case class BranchEntity(
      id: String,
      repoId: String,
      name: String,
      isProtected: Boolean,
      requirePullRequest: Boolean,
      requiredReviewers: Long,
      admins: Set[String],
      writers: Set[String]
  )
  case class PullRequestEntity(
      id: String,
      repoId: String,
      number: Int,
      title: String,
      authorId: String,
      state: String,
      locked: Boolean,
      writers: Set[String],
      readers: Set[String]
  )

  // ============================================================================
  // In-Memory Data Store
  // ============================================================================

  object DataStore {
    val users = Map(
      "alice" -> User("alice", "alice", Set("acme"), Map("acme-core" -> "admin")),
      "bob" -> User("bob", "bob", Set("acme"), Map("acme-core" -> "push")),
      "carol" -> User("carol", "carol", Set("acme"), Map("acme-core" -> "pull")),
      "external" -> User("external", "external", Set.empty, Map.empty)
    )

    val orgs = Map(
      "acme" -> OrgEntity(
        id = "acme",
        login = "acme-corp",
        name = "Acme Corporation",
        plan = "enterprise",
        defaultRepoPermission = "read",
        members = Set("alice", "bob", "carol"),
        admins = Set("alice"),
        membersCanCreateRepos = true
      )
    )

    val teams = Map(
      "acme-core" -> TeamEntity(
        id = "acme-core",
        orgId = "acme",
        name = "Core Team",
        slug = "core-team",
        privacy = "closed",
        permission = "push"
      )
    )

    // Compute effective permissions based on team membership
    def getRepoPermissions(repoId: String): (Set[String], Set[String], Set[String]) = {
      val repo = repos(repoId)
      val orgAdmins = orgs.get(repo.orgId).map(_.admins).getOrElse(Set.empty)

      // Team members get permissions based on team permission level
      val teamWriters = users.values.filter(_.teams.values.exists(p => p == "push" || p == "admin")).map(_.id).toSet
      val teamAdmins = users.values.filter(_.teams.values.exists(_ == "admin")).map(_.id).toSet
      val teamReaders = users.values.filter(_.teams.values.exists(_ == "pull")).map(_.id).toSet

      val admins = orgAdmins ++ teamAdmins ++ repo.admins
      val writers = teamWriters ++ repo.writers
      val readers = teamReaders ++ repo.readers

      (admins, writers, readers)
    }

    val repos: Map[String, RepoEntity] = {
      // Base repos without computed permissions
      val baseRepos = Map(
        "public-lib" -> RepoEntity(
          id = "public-lib",
          orgId = "acme",
          name = "public-lib",
          visibility = "public",
          archived = false,
          defaultBranch = "main",
          allowForking = true,
          admins = Set.empty,
          writers = Set.empty,
          readers = Set.empty
        ),
        "private-app" -> RepoEntity(
          id = "private-app",
          orgId = "acme",
          name = "private-app",
          visibility = "private",
          archived = false,
          defaultBranch = "main",
          allowForking = false,
          admins = Set.empty,
          writers = Set.empty,
          readers = Set("external") // External user is a collaborator
        )
      )

      // Compute effective permissions
      baseRepos.map { case (id, repo) =>
        val orgAdmins = orgs.get(repo.orgId).map(_.admins).getOrElse(Set.empty)
        val teamWriters = users.values.filter(_.teams.values.exists(p => p == "push" || p == "admin")).map(_.id).toSet
        val teamAdmins = users.values.filter(_.teams.values.exists(_ == "admin")).map(_.id).toSet
        val teamReaders = users.values.filter(_.teams.values.exists(_ == "pull")).map(_.id).toSet

        id -> repo.copy(
          admins = orgAdmins ++ teamAdmins,
          writers = teamWriters,
          readers = teamReaders ++ repo.readers
        )
      }
    }

    val branches: Map[String, BranchEntity] = {
      Map(
        "public-lib-main" -> BranchEntity(
          id = "public-lib-main",
          repoId = "public-lib",
          name = "main",
          isProtected = true,
          requirePullRequest = true,
          requiredReviewers = 2,
          admins = repos("public-lib").admins,
          writers = repos("public-lib").writers
        ),
        "public-lib-dev" -> BranchEntity(
          id = "public-lib-dev",
          repoId = "public-lib",
          name = "develop",
          isProtected = false,
          requirePullRequest = false,
          requiredReviewers = 0,
          admins = repos("public-lib").admins,
          writers = repos("public-lib").writers
        ),
        "private-app-main" -> BranchEntity(
          id = "private-app-main",
          repoId = "private-app",
          name = "main",
          isProtected = true,
          requirePullRequest = true,
          requiredReviewers = 1,
          admins = repos("private-app").admins,
          writers = repos("private-app").writers
        )
      )
    }

    val pullRequests: Map[String, PullRequestEntity] = {
      Map(
        "pr-123" -> PullRequestEntity(
          id = "pr-123",
          repoId = "public-lib",
          number = 123,
          title = "Add new feature",
          authorId = "bob",
          state = "open",
          locked = false,
          writers = repos("public-lib").writers,
          readers = repos("public-lib").readers
        ),
        "pr-456" -> PullRequestEntity(
          id = "pr-456",
          repoId = "private-app",
          number = 456,
          title = "Fix bug",
          authorId = "external",
          state = "open",
          locked = false,
          writers = repos("private-app").writers,
          readers = repos("private-app").readers
        )
      )
    }
  }

  // ============================================================================
  // Entity Fetchers - Load entities and convert to generated Cedar entity classes
  // ============================================================================

  /** Fetcher for Organization entities.
    */
  class OrganizationFetcher extends EntityFetcher[Future, GitHub.Entity.Organization, OrganizationId] {
    def fetch(id: OrganizationId): Future[Option[GitHub.Entity.Organization]] =
      Future.successful(DataStore.orgs.get(id.value).map { org =>
        GitHub.Entity.Organization(
          id = OrganizationId(org.id),
          login = org.login,
          name = org.name,
          plan = org.plan,
          defaultrepopermission = org.defaultRepoPermission
        )
      })
  }

  /** Fetcher for Repository entities.
    */
  class RepositoryFetcher extends EntityFetcher[Future, GitHub.Entity.Repository, RepositoryId] {
    def fetch(id: RepositoryId): Future[Option[GitHub.Entity.Repository]] =
      Future.successful(DataStore.repos.get(id.value).map { repo =>
        GitHub.Entity.Repository(
          id = RepositoryId(repo.id),
          organizationId = OrganizationId(repo.orgId),
          name = repo.name,
          visibility = repo.visibility,
          archived = repo.archived,
          defaultbranch = repo.defaultBranch,
          allowforking = repo.allowForking
        )
      })
  }

  /** Fetcher for Branch entities.
    */
  class BranchFetcher extends EntityFetcher[Future, GitHub.Entity.Branch, BranchId] {
    def fetch(id: BranchId): Future[Option[GitHub.Entity.Branch]] =
      Future.successful(DataStore.branches.get(id.value).map { branch =>
        GitHub.Entity.Branch(
          id = BranchId(branch.id),
          repositoryId = RepositoryId(branch.repoId),
          name = branch.name,
          `protected` = branch.isProtected,
          requirepullrequest = branch.requirePullRequest,
          requiredreviewers = branch.requiredReviewers
        )
      })
  }

  /** Fetcher for PullRequest entities.
    */
  class PullRequestFetcher extends EntityFetcher[Future, GitHub.Entity.PullRequest, PullRequestId] {
    def fetch(id: PullRequestId): Future[Option[GitHub.Entity.PullRequest]] =
      Future.successful(DataStore.pullRequests.get(id.value).map { pr =>
        GitHub.Entity.PullRequest(
          id = PullRequestId(pr.id),
          repositoryId = RepositoryId(pr.repoId),
          number = pr.number.toLong,
          title = pr.title,
          author = pr.authorId,
          state = pr.state,
          draft = false,
          targetbranch = "main"
        )
      })
  }

  /** Fetcher for User entities (principals).
    */
  class UserFetcher extends EntityFetcher[Future, GitHub.Entity.User, UserId] {
    def fetch(id: UserId): Future[Option[GitHub.Entity.User]] =
      Future.successful(DataStore.users.get(id.value).map { user =>
        GitHub.Entity.User(
          id = UserId(user.id),
          login = user.login,
          name = user.login,
          verified = true,
          twofactorenabled = false
        )
      })
  }

  // ============================================================================
  // Principal resolution - returns principal entity, not CedarPrincipal
  // ============================================================================

  def resolvePrincipal(principal: Principal): Future[Option[GitHub.Entity.User]] = {
    val principalId = principal match {
      case GitHub.Principal.User(userId) => userId.value
      case _                             => principal.entityId
    }

    Future.successful(
      DataStore.users.get(principalId).map { userData =>
        GitHub.Entity.User(
          id = UserId(userData.id),
          login = userData.login,
          name = userData.login,
          verified = true,
          twofactorenabled = false
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
    .register[GitHub.Entity.Organization, OrganizationId](new OrganizationFetcher())
    .register[GitHub.Entity.Repository, RepositoryId](new RepositoryFetcher())
    .register[GitHub.Entity.Branch, BranchId](new BranchFetcher())
    .register[GitHub.Entity.PullRequest, PullRequestId](new PullRequestFetcher())
    .register[GitHub.Entity.User, UserId](new UserFetcher())
    .build()

  // Load Cedar engine with policies from resources
  val cedarEngine: CedarEngine[Future] = CedarEngine.fromResources(
    policiesPath = "policies",
    policyFiles = Seq("github.cedar")
  )

  private val cedarRuntime: CedarRuntime[Future, GitHub.Entity.User] =
    CedarRuntime[Future, GitHub.Entity.User](cedarEngine, entityStore, CedarRuntime.resolverFrom(resolvePrincipal))

  // Helper to create CedarSession for a user
  def sessionFor(user: User): CedarSession[Future] =
    cedarRuntime.session(GitHub.Principal.User(UserId(user.id)))

  // FlatMap instance for Future
  given futureFlatMap: FlatMap[Future] = FlatMap.futureInstance

  // ============================================================================
  // Example Usage
  // ============================================================================

  def main(args: Array[String]): Unit = {
    println("=== GitHub RBAC Example with Cedar Policies ===\n")
    println("Authorization decisions are made by Cedar policies in github.cedar,")
    println("NOT by Scala code. EntityFetchers load entities with role-based attributes.\n")

    val alice = DataStore.users("alice") // Org admin
    val bob = DataStore.users("bob") // Team member (push)
    val carol = DataStore.users("carol") // Team member (pull)
    val external = DataStore.users("external") // Outside collaborator

    // -------------------------------------------------------------------------
    // 1. Repository Visibility
    // -------------------------------------------------------------------------
    println("1. Repository Visibility (Public vs Private)")
    println("-" * 60)

    // External user can view public repo
    {
      given CedarSession[Future] = sessionFor(external)
      val result = Await.result(GitHub.Repository.View.on(RepositoryId("public-lib")).run, 5.seconds)
      println(s"   External -> public-lib view:  ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    // External user (as collaborator) can view private repo
    {
      given CedarSession[Future] = sessionFor(external)
      val result = Await.result(GitHub.Repository.View.on(RepositoryId("private-app")).run, 5.seconds)
      println(s"   External -> private-app view: ${if result.isRight then "ALLOWED" else "DENIED"} (collaborator)")
    }

    // External user cannot push to private repo (only pull access)
    {
      given CedarSession[Future] = sessionFor(external)
      val result = Await.result(GitHub.Repository.Push.on(RepositoryId("private-app")).run, 5.seconds)
      println(s"   External -> private-app push: ${if result.isRight then "ALLOWED" else "DENIED"} (pull-only)")
    }

    // -------------------------------------------------------------------------
    // 2. Team-Based Permissions
    // -------------------------------------------------------------------------
    println("\n2. Team-Based Permissions")
    println("-" * 60)

    // Carol (pull access) can view but not push
    {
      given CedarSession[Future] = sessionFor(carol)
      val viewResult = Await.result(GitHub.Repository.View.on(RepositoryId("public-lib")).run, 5.seconds)
      val pushResult = Await.result(GitHub.Repository.Push.on(RepositoryId("public-lib")).run, 5.seconds)
      println(s"   Carol (pull) -> view: ${if viewResult.isRight then "ALLOWED" else "DENIED"}")
      println(s"   Carol (pull) -> push: ${if pushResult.isRight then "ALLOWED" else "DENIED"}")
    }

    // Bob (push access) can push
    {
      given CedarSession[Future] = sessionFor(bob)
      val pushResult = Await.result(GitHub.Repository.Push.on(RepositoryId("public-lib")).run, 5.seconds)
      println(s"   Bob (push)   -> push: ${if pushResult.isRight then "ALLOWED" else "DENIED"}")
    }

    // Alice (admin) can delete
    {
      given CedarSession[Future] = sessionFor(alice)
      val deleteResult = Await.result(GitHub.Repository.Delete.on(RepositoryId("public-lib")).run, 5.seconds)
      println(s"   Alice (admin) -> delete: ${if deleteResult.isRight then "ALLOWED" else "DENIED"}")
    }

    // -------------------------------------------------------------------------
    // 3. Protected Branch Rules
    // -------------------------------------------------------------------------
    println("\n3. Protected Branch Rules")
    println("-" * 60)

    // Bob can push to unprotected develop branch
    {
      given CedarSession[Future] = sessionFor(bob)
      val result = Await.result(GitHub.Branch.Push.on(BranchId("public-lib-dev")).run, 5.seconds)
      println(s"   Bob -> push to develop (unprotected): ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    // Bob cannot push to protected main branch
    {
      given CedarSession[Future] = sessionFor(bob)
      val result = Await.result(GitHub.Branch.Push.on(BranchId("public-lib-main")).run, 5.seconds)
      println(s"   Bob -> push to main (protected):    ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    // Alice (admin) can push to protected main branch
    {
      given CedarSession[Future] = sessionFor(alice)
      val result = Await.result(GitHub.Branch.Push.on(BranchId("public-lib-main")).run, 5.seconds)
      println(s"   Alice -> push to main (protected):  ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    // -------------------------------------------------------------------------
    // 4. PR Author Permissions
    // -------------------------------------------------------------------------
    println("\n4. Pull Request Author Permissions")
    println("-" * 60)

    // Bob (PR author) can update his own PR
    {
      given CedarSession[Future] = sessionFor(bob)
      val result = Await.result(GitHub.PullRequest.Update.on(PullRequestId("pr-123")).run, 5.seconds)
      println(s"   Bob -> update PR #123 (author):    ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    // Carol (not author, but reader) cannot update Bob's PR
    {
      given CedarSession[Future] = sessionFor(carol)
      val result = Await.result(GitHub.PullRequest.Update.on(PullRequestId("pr-123")).run, 5.seconds)
      println(s"   Carol -> update PR #123 (not author): ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    // External can update their own PR on private repo
    {
      given CedarSession[Future] = sessionFor(external)
      val result = Await.result(GitHub.PullRequest.Update.on(PullRequestId("pr-456")).run, 5.seconds)
      println(s"   External -> update PR #456 (author):  ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    // -------------------------------------------------------------------------
    // 5. Organization Permissions
    // -------------------------------------------------------------------------
    println("\n5. Organization Permissions")
    println("-" * 60)

    // Alice (org admin) can manage members
    {
      given CedarSession[Future] = sessionFor(alice)
      val result = Await.result(GitHub.Organization.ManageMembers.on(OrganizationId("acme")).run, 5.seconds)
      println(s"   Alice (admin) -> manageMembers: ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    // Bob (member) cannot manage members
    {
      given CedarSession[Future] = sessionFor(bob)
      val result = Await.result(GitHub.Organization.ManageMembers.on(OrganizationId("acme")).run, 5.seconds)
      println(s"   Bob (member) -> manageMembers:  ${if result.isRight then "ALLOWED" else "DENIED"}")
    }

    println("\n=== Done ===")
  }
}
