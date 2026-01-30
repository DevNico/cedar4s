package example

import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

// Import generated Cedar types
import example.docshare.cedar.DocShare
import example.docshare.cedar.EntityIds.{DocumentId, FolderId, CommentId, UserId}

// Import cedar4s types
import cedar4s.auth.*
import cedar4s.client.{CedarEngine, CedarRuntime}
import cedar4s.entities.*
import cedar4s.schema.CedarEntityUid
// Import type class instances for Future
import cedar4s.capability.instances.{futureSync, futureMonadError}

// Make FlatMap available for deferred checks (.on syntax)
given cedar4s.auth.FlatMap[Future] = cedar4s.auth.FlatMap.futureInstance

/** Document Sharing Example
  *
  * Demonstrates cedar4s with REAL Cedar policy evaluation:
  *   1. EntityFetchers load entities with attributes from data store
  *   2. CedarEngine evaluates Cedar policies (.cedar files)
  *   3. CedarSession bridges the DSL to the Cedar engine
  *
  * The authorization logic is in docshare.cedar, NOT in Scala code!
  */
object DocShareExample {

  // ============================================================================
  // Domain Models (Your application's models - separate from Cedar entities)
  // ============================================================================

  case class UserModel(id: String, email: String, name: String)
  case class FolderModel(
      id: String,
      name: String,
      ownerId: String,
      editors: Set[String],
      viewers: Set[String],
      viewerGroups: Set[String]
  )
  case class DocumentModel(
      id: String,
      folderId: String,
      name: String,
      ownerId: String,
      editors: Set[String],
      viewers: Set[String],
      locked: Boolean
  )
  case class CommentModel(id: String, documentId: String, folderId: String, authorId: String, text: String)

  // ============================================================================
  // In-Memory Data Store
  // ============================================================================

  object DataStore {
    val users = Map(
      "alice" -> UserModel("alice", "alice@example.com", "Alice"),
      "bob" -> UserModel("bob", "bob@example.com", "Bob"),
      "charlie" -> UserModel("charlie", "charlie@example.com", "Charlie")
    )

    val folders = Map(
      "folder-1" -> FolderModel("folder-1", "Shared Docs", "alice", Set("bob"), Set("charlie"), Set.empty)
    )

    val documents = Map(
      "doc-1" -> DocumentModel("doc-1", "folder-1", "Meeting Notes", "alice", Set("bob"), Set.empty, locked = false),
      "doc-2" -> DocumentModel("doc-2", "folder-1", "Budget", "alice", Set.empty, Set.empty, locked = true)
    )

    val comments = Map(
      "comment-1" -> CommentModel("comment-1", "doc-1", "folder-1", "bob", "Great notes!")
    )
  }

  // ============================================================================
  // Entity Fetchers - Load entities and convert to generated Cedar entity classes
  // ============================================================================

  /** Fetcher for Folder entities.
    *
    * Maps from our domain model (FolderModel) to the generated Cedar entity (DocShare.Entity.Folder) Note: The fetcher
    * uses FolderId (newtype) as the ID type to match the generated entity.
    */
  class FolderFetcher extends EntityFetcher[Future, DocShare.Entity.Folder, FolderId] {
    def fetch(id: FolderId): Future[Option[DocShare.Entity.Folder]] =
      Future.successful(DataStore.folders.get(id.value).map { folder =>
        DocShare.Entity.Folder(
          id = FolderId(folder.id),
          name = folder.name,
          owner = folder.ownerId,
          editors = folder.editors,
          viewers = folder.viewers,
          viewergroups = folder.viewerGroups
        )
      })
  }

  /** Fetcher for Document entities.
    *
    * Maps from our domain model (DocumentModel) to the generated Cedar entity (DocShare.Entity.Document)
    */
  class DocumentFetcher extends EntityFetcher[Future, DocShare.Entity.Document, DocumentId] {
    def fetch(id: DocumentId): Future[Option[DocShare.Entity.Document]] =
      Future.successful(DataStore.documents.get(id.value).map { doc =>
        DocShare.Entity.Document(
          id = DocumentId(doc.id),
          folderId = FolderId(doc.folderId),
          name = doc.name,
          owner = doc.ownerId,
          editors = doc.editors,
          viewers = doc.viewers,
          locked = doc.locked
        )
      })
  }

  /** Fetcher for Comment entities.
    *
    * Maps from our domain model (CommentModel) to the generated Cedar entity (DocShare.Entity.Comment)
    */
  class CommentFetcher extends EntityFetcher[Future, DocShare.Entity.Comment, CommentId] {
    def fetch(id: CommentId): Future[Option[DocShare.Entity.Comment]] =
      Future.successful(DataStore.comments.get(id.value).map { comment =>
        DocShare.Entity.Comment(
          id = CommentId(comment.id),
          documentId = DocumentId(comment.documentId),
          author = comment.authorId,
          text = comment.text
        )
      })
  }

  /** Fetcher for User entities (principals).
    *
    * Maps from our domain model (UserModel) to the generated Cedar entity (DocShare.Entity.User)
    */
  class UserFetcher extends EntityFetcher[Future, DocShare.Entity.User, UserId] {
    def fetch(id: UserId): Future[Option[DocShare.Entity.User]] =
      Future.successful(DataStore.users.get(id.value).map { user =>
        DocShare.Entity.User(
          id = UserId(user.id),
          email = user.email,
          name = user.name
        )
      })
  }

  // ============================================================================
  // Principal resolution - returns principal entity, not CedarPrincipal
  // ============================================================================

  def resolvePrincipal(principal: Principal): Future[Option[DocShare.Entity.User]] = {
    val principalId = principal match {
      case DocShare.Principal.User(userId) => userId.value
      case _                               => principal.entityId
    }

    Future.successful(
      DataStore.users.get(principalId).map { userData =>
        DocShare.Entity.User(
          id = UserId(userData.id),
          email = userData.email,
          name = userData.name
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
    .register[DocShare.Entity.Folder, FolderId](new FolderFetcher())
    .register[DocShare.Entity.Document, DocumentId](new DocumentFetcher())
    .register[DocShare.Entity.Comment, CommentId](new CommentFetcher())
    .register[DocShare.Entity.User, UserId](new UserFetcher())
    .build()

  // Load Cedar engine with policies from resources
  val cedarEngine: CedarEngine[Future] = CedarEngine.fromResources[Future](
    policiesPath = "policies",
    policyFiles = Seq("docshare.cedar")
  )

  private val cedarRuntime: CedarRuntime[Future, DocShare.Entity.User] =
    CedarRuntime[Future, DocShare.Entity.User](cedarEngine, entityStore, CedarRuntime.resolverFrom(resolvePrincipal))

  // Helper to create CedarSession for a user
  def sessionFor(user: UserModel): CedarSession[Future] =
    cedarRuntime.session(DocShare.Principal.User(UserId(user.id)))

  // ============================================================================
  // Example Usage
  // ============================================================================

  def main(args: Array[String]): Unit = {
    val alice = DataStore.users("alice")
    val bob = DataStore.users("bob")
    val charlie = DataStore.users("charlie")

    println("=== cedar4s Example: Document Sharing with Cedar Policies ===\n")
    println("Authorization decisions are made by Cedar policies in docshare.cedar,")
    println("NOT by Scala code. EntityFetchers load entities with attributes.\n")

    // -------------------------------------------------------------------------
    // Pattern 1: Deferred syntax with .on(id)
    // -------------------------------------------------------------------------
    println("Pattern 1: Deferred syntax with .on(id)")
    println("-" * 50)

    println(s"  Check: DocShare.Document.View.on(DocumentId(\"doc-1\"))")
    println(s"    This creates a DeferredAuthCheck that resolves parents via EntityStore")
    println(s"    Requires CedarSession[F] in scope (provides EntityStore for resolution)")

    // -------------------------------------------------------------------------
    // Pattern 2: Resolve deferred checks to compose them
    // -------------------------------------------------------------------------
    println("\nPattern 2: Deferred checks resolve before composition")
    println("-" * 50)
    println("  Note: DeferredAuthCheck.resolve returns F[AuthCheck.Single]")
    println("  Composition (& and |) requires resolving first.")

    // -------------------------------------------------------------------------
    // Pattern 3: Execute with CedarSession (REAL Cedar policy evaluation)
    // -------------------------------------------------------------------------
    println("\nPattern 3: Execute authorization checks (Cedar policies)")
    println("-" * 50)

    // Test as Alice (owner)
    {
      given CedarSession[Future] = sessionFor(alice)

      println(s"  As Alice (owner of folder-1 and documents):")

      val result1 = Await.result(DocShare.Document.View.on(DocumentId("doc-1")).run, 5.seconds)
      println(s"    DocShare.Document.View(doc-1)   = ${if result1.isRight then "ALLOWED" else "DENIED"}")

      val result2 = Await.result(DocShare.Document.Edit.on(DocumentId("doc-2")).run, 5.seconds)
      println(
        s"    DocShare.Document.Edit(doc-2)   = ${if result2.isRight then "ALLOWED" else "DENIED"} (locked but owner)"
      )

      val result3 = Await.result(DocShare.Document.Delete.on(DocumentId("doc-1")).run, 5.seconds)
      println(s"    DocShare.Document.Delete(doc-1) = ${if result3.isRight then "ALLOWED" else "DENIED"}")
    }

    // Test as Bob (editor)
    {
      given CedarSession[Future] = sessionFor(bob)

      println(s"\n  As Bob (editor of doc-1):")

      val result1 = Await.result(DocShare.Document.View.on(DocumentId("doc-1")).run, 5.seconds)
      println(s"    DocShare.Document.View(doc-1)   = ${if result1.isRight then "ALLOWED" else "DENIED"}")

      val result2 = Await.result(DocShare.Document.Edit.on(DocumentId("doc-1")).run, 5.seconds)
      println(s"    DocShare.Document.Edit(doc-1)   = ${if result2.isRight then "ALLOWED" else "DENIED"} (unlocked)")

      val result3 = Await.result(DocShare.Document.Edit.on(DocumentId("doc-2")).run, 5.seconds)
      println(
        s"    DocShare.Document.Edit(doc-2)   = ${if result3.isRight then "ALLOWED" else "DENIED"} (not an editor)"
      )

      val result4 = Await.result(DocShare.Document.Delete.on(DocumentId("doc-1")).run, 5.seconds)
      println(s"    DocShare.Document.Delete(doc-1) = ${if result4.isRight then "ALLOWED" else "DENIED"} (not owner)")
    }

    // Test as Charlie (viewer)
    {
      given CedarSession[Future] = sessionFor(charlie)

      println(s"\n  As Charlie (viewer of folder-1):")

      val result1 = Await.result(DocShare.Folder.View.on(FolderId("folder-1")).run, 5.seconds)
      println(s"    DocShare.Folder.View(folder-1)  = ${if result1.isRight then "ALLOWED" else "DENIED"}")

      val result2 = Await.result(DocShare.Folder.Edit.on(FolderId("folder-1")).run, 5.seconds)
      println(s"    DocShare.Folder.Edit(folder-1)  = ${if result2.isRight then "ALLOWED" else "DENIED"} (viewer only)")
    }

    // -------------------------------------------------------------------------
    // Pattern 4: Nested hierarchy (Comment -> Document -> Folder)
    // -------------------------------------------------------------------------
    println("\nPattern 4: Nested hierarchies")
    println("-" * 50)

    println(s"  DocShare.Comment.View.on(CommentId(\"comment-1\"))")
    println(s"    This deferred check will resolve parents: Comment -> Document -> Folder")

    // Test comment permissions
    {
      given CedarSession[Future] = sessionFor(bob)

      println(s"\n  As Bob (comment author):")
      val result1 = Await.result(DocShare.Comment.Edit.on(CommentId("comment-1")).run, 5.seconds)
      println(s"    DocShare.Comment.Edit(comment-1) = ${if result1.isRight then "ALLOWED" else "DENIED"}")

      val result2 = Await.result(DocShare.Comment.Delete.on(CommentId("comment-1")).run, 5.seconds)
      println(s"    DocShare.Comment.Delete(comment-1) = ${if result2.isRight then "ALLOWED" else "DENIED"}")
    }

    {
      given CedarSession[Future] = sessionFor(alice)

      println(s"\n  As Alice (not comment author):")
      val result1 = Await.result(DocShare.Comment.View.on(CommentId("comment-1")).run, 5.seconds)
      println(s"    DocShare.Comment.View(comment-1) = ${if result1.isRight then "ALLOWED" else "DENIED"}")

      val result2 = Await.result(DocShare.Comment.Edit.on(CommentId("comment-1")).run, 5.seconds)
      println(s"    DocShare.Comment.Edit(comment-1) = ${if result2.isRight then "ALLOWED" else "DENIED"}")
    }

    println("\n=== Done ===")
  }
}
