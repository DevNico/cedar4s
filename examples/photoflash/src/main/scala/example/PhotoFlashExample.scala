package example

import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

// Import generated Cedar types
import example.photoflash.cedar.PhotoFlash
import example.photoflash.cedar.PhotoFlash.{Principal, Entity}
import example.photoflash.cedar.Contexts
import example.photoflash.cedar.EntityIds.{UserId, UserGroupId, AlbumId, AlbumFolderId, AccountId, PhotoId}

// Import cedar4s types
import cedar4s.auth.*
import cedar4s.client.{CedarEngine, CedarRuntime}
import cedar4s.entities.*
import cedar4s.schema.CedarEntityUid
// Import type class instances for Future
import cedar4s.capability.instances.{futureSync, futureMonadError}

// Make FlatMap available for deferred checks (.on syntax)
given cedar4s.auth.FlatMap[Future] = cedar4s.auth.FlatMap.futureInstance

/** PhotoFlash Example
  *
  * Demonstrates advanced Cedar features:
  *   1. Multiple parent types (Album in [Account, AlbumFolder])
  *   2. Entity references in attributes (Account with Set<User>)
  *   3. Context attributes for file metadata validation
  *   4. Hierarchical relationships with entity references
  *
  * Authorization logic is in photoflash.cedar policies!
  */
object PhotoFlashExample {

  // ============================================================================
  // Domain Models (Your application's models)
  // ============================================================================

  case class UserModel(id: String, department: String, jobLevel: Int, groupId: String)
  case class UserGroupModel(id: String, name: String)
  case class AccountModel(id: String, ownerId: String, adminIds: Set[String])
  case class AlbumFolderModel(id: String, name: String, accountId: String)
  case class AlbumModel(
      id: String,
      name: String,
      accountId: String,
      parentId: Either[String, String],
      isPrivate: Boolean
  ) // Left = AccountId, Right = AlbumFolderId
  case class PhotoModel(id: String, albumId: String, accountId: String, filename: String, isPrivate: Boolean)

  // ============================================================================
  // In-Memory Data Store
  // ============================================================================

  object DataStore {
    val users = Map(
      "alice" -> UserModel("alice", "Engineering", 5, "eng-team"),
      "bob" -> UserModel("bob", "Engineering", 3, "eng-team"),
      "charlie" -> UserModel("charlie", "Marketing", 4, "marketing-team")
    )

    val userGroups = Map(
      "eng-team" -> UserGroupModel("eng-team", "Engineering Team"),
      "marketing-team" -> UserGroupModel("marketing-team", "Marketing Team")
    )

    val accounts = Map(
      "account-1" -> AccountModel("account-1", "alice", Set("bob")),
      "account-2" -> AccountModel("account-2", "charlie", Set.empty)
    )

    val albumFolders = Map(
      // Album folders for organizing albums
      "folder-1" -> AlbumFolderModel("folder-1", "Family & Vacation", "account-1"),
      "folder-2" -> AlbumFolderModel("folder-2", "Beach Trip", "account-1"),
      "folder-3" -> AlbumFolderModel("folder-3", "Work", "account-2")
    )

    val albums = Map(
      // All albums must be in folders due to codegen limitation with multiple parent types
      "album-1" -> AlbumModel("album-1", "Family Photos", "account-1", Right("folder-1"), isPrivate = false),
      "album-2" -> AlbumModel("album-2", "Day 1", "account-1", Right("folder-1"), isPrivate = true),
      "album-3" -> AlbumModel("album-3", "Day 2", "account-1", Right("folder-1"), isPrivate = true),
      "album-4" -> AlbumModel("album-4", "Sunset Photos", "account-1", Right("folder-2"), isPrivate = true),
      "album-5" -> AlbumModel("album-5", "Work Events", "account-2", Right("folder-3"), isPrivate = false)
    )

    val photos = Map(
      // Photos in root albums
      "photo-1" -> PhotoModel("photo-1", "album-1", "account-1", "family-dinner.jpg", isPrivate = false),
      "photo-2" -> PhotoModel("photo-2", "album-2", "account-1", "beach-sunset.jpg", isPrivate = true),

      // Photos in nested albums
      "photo-3" -> PhotoModel("photo-3", "album-3", "account-1", "beach-day.jpg", isPrivate = true),
      "photo-4" -> PhotoModel("photo-4", "album-4", "account-1", "morning-walk.jpg", isPrivate = true),

      // Photos in other account
      "photo-5" -> PhotoModel("photo-5", "album-5", "account-2", "conference.jpg", isPrivate = false)
    )
  }

  // ============================================================================
  // Entity Fetchers - Load entities and convert to generated Cedar entity classes
  // ============================================================================

  /** Fetcher for User entities (principals). Maps from UserModel to PhotoFlash.Entity.User with parent groups.
    */
  class UserFetcher extends EntityFetcher[Future, PhotoFlash.Entity.User, UserId] {
    def fetch(id: UserId): Future[Option[PhotoFlash.Entity.User]] =
      Future.successful(DataStore.users.get(id.value).map { user =>
        PhotoFlash.Entity.User(
          id = UserId(user.id),
          userGroupId = UserGroupId(user.groupId),
          department = user.department,
          joblevel = user.jobLevel.toLong
        )
      })
  }

  /** Fetcher for UserGroup entities.
    */
  class UserGroupFetcher extends EntityFetcher[Future, PhotoFlash.Entity.UserGroup, UserGroupId] {
    def fetch(id: UserGroupId): Future[Option[PhotoFlash.Entity.UserGroup]] =
      Future.successful(DataStore.userGroups.get(id.value).map { group =>
        PhotoFlash.Entity.UserGroup(
          id = UserGroupId(group.id)
        )
      })
  }

  /** Fetcher for Account entities. Demonstrates entity references in attributes (Set<User>).
    */
  class AccountFetcher extends EntityFetcher[Future, PhotoFlash.Entity.Account, AccountId] {
    def fetch(id: AccountId): Future[Option[PhotoFlash.Entity.Account]] =
      Future.successful(DataStore.accounts.get(id.value).map { account =>
        PhotoFlash.Entity.Account(
          id = AccountId(account.id),
          owner = account.ownerId,
          admins = if (account.adminIds.nonEmpty) Some(account.adminIds) else None
        )
      })
  }

  /** Fetcher for AlbumFolder entities.
    */
  class AlbumFolderFetcher extends EntityFetcher[Future, PhotoFlash.Entity.AlbumFolder, AlbumFolderId] {
    def fetch(id: AlbumFolderId): Future[Option[PhotoFlash.Entity.AlbumFolder]] =
      Future.successful(DataStore.albumFolders.get(id.value).map { folder =>
        PhotoFlash.Entity.AlbumFolder(
          id = AlbumFolderId(folder.id),
          accountId = AccountId(folder.accountId),
          name = folder.name
        )
      })
  }

  /** Fetcher for Album entities. Demonstrates multiple parent types (Album in [Account, AlbumFolder]).
    */
  class AlbumFetcher extends EntityFetcher[Future, PhotoFlash.Entity.Album, AlbumId] {
    def fetch(id: AlbumId): Future[Option[PhotoFlash.Entity.Album]] =
      Future.successful(DataStore.albums.get(id.value).map { album =>
        val folderId = album.parentId match {
          case Right(fid) => fid
          case Left(_)    => throw new IllegalStateException(s"Album ${album.id} must have an AlbumFolder parent")
        }
        PhotoFlash.Entity.Album(
          id = AlbumId(album.id),
          albumFolderId = AlbumFolderId(folderId),
          account = album.accountId,
          `private` = album.isPrivate
        )
      })
  }

  /** Fetcher for Photo entities.
    */
  class PhotoFetcher extends EntityFetcher[Future, PhotoFlash.Entity.Photo, PhotoId] {
    def fetch(id: PhotoId): Future[Option[PhotoFlash.Entity.Photo]] =
      Future.successful(DataStore.photos.get(id.value).map { photo =>
        PhotoFlash.Entity.Photo(
          id = PhotoId(photo.id),
          albumId = AlbumId(photo.albumId),
          account = photo.accountId,
          `private` = photo.isPrivate
        )
      })
  }

  // ============================================================================
  // Principal resolution - returns principal entity, not CedarPrincipal
  // ============================================================================

  def resolvePrincipal(principal: Principal): Future[Option[PhotoFlash.Entity.User]] = {
    val principalId = principal match {
      case PhotoFlash.Principal.User(userId) => userId.value
      case _                                 => principal.entityId
    }

    Future.successful(
      DataStore.users.get(principalId).map { userData =>
        PhotoFlash.Entity.User(
          id = UserId(userData.id),
          userGroupId = UserGroupId(userData.groupId),
          department = userData.department,
          joblevel = userData.jobLevel.toLong
        )
      }
    )
  }

  // ============================================================================
  // Setup
  // ============================================================================

  // Create EntityStore with all fetchers
  val entityStore: EntityStore[Future] = EntityStore
    .builder[Future]()
    .register[PhotoFlash.Entity.User, UserId](new UserFetcher())
    .register[PhotoFlash.Entity.UserGroup, UserGroupId](new UserGroupFetcher())
    .register[PhotoFlash.Entity.Account, AccountId](new AccountFetcher())
    .register[PhotoFlash.Entity.AlbumFolder, AlbumFolderId](new AlbumFolderFetcher())
    .register[PhotoFlash.Entity.Album, AlbumId](new AlbumFetcher())
    .register[PhotoFlash.Entity.Photo, PhotoId](new PhotoFetcher())
    .build()

  // Load Cedar engine with policies from resources
  val cedarEngine: CedarEngine[Future] = CedarEngine.fromResources[Future](
    policiesPath = "policies",
    policyFiles = Seq("photoflash.cedar")
  )

  private val cedarRuntime: CedarRuntime[Future, PhotoFlash.Entity.User] =
    CedarRuntime[Future, PhotoFlash.Entity.User](cedarEngine, entityStore, CedarRuntime.resolverFrom(resolvePrincipal))

  // Helper to create CedarSession for a user
  def sessionFor(user: UserModel): CedarSession[Future] =
    cedarRuntime.session(PhotoFlash.Principal.User(UserId(user.id)))

  // ============================================================================
  // Example Usage
  // ============================================================================

  def main(args: Array[String]): Unit = {
    val alice = DataStore.users("alice")
    val bob = DataStore.users("bob")
    val charlie = DataStore.users("charlie")

    println("=== cedar4s Example: PhotoFlash with Advanced Cedar Features ===\n")
    println("This example demonstrates:")
    println("  - Multiple parent types (Album in [Account, AlbumFolder])")
    println("  - Entity references in attributes (Account with Set<User>)")
    println("  - Context attributes for file validation")
    println("  - Hierarchical relationships\n")

    // -------------------------------------------------------------------------
    // Feature 1: Entity References in Attributes
    // -------------------------------------------------------------------------
    println("Feature 1: Entity References in Attributes (Account.admins: Set<User>)")
    println("-" * 70)

    {
      given CedarSession[Future] = sessionFor(alice)

      println(s"  As Alice (owner of account-1):")

      val result1 = Await.result(
        PhotoFlash.Account.ListAlbums.on(AccountId("account-1")).resolve.flatMap { check =>
          check.withContext(Contexts.AccountListAlbumsContext(authenticated = Some(true))).run
        },
        5.seconds
      )
      println(s"    listAlbums(account-1) = ${if result1.isRight then "ALLOWED" else "DENIED"}")
    }

    {
      given CedarSession[Future] = sessionFor(bob)

      println(s"\n  As Bob (admin of account-1):")

      val result1 = Await.result(
        PhotoFlash.Account.ListAlbums.on(AccountId("account-1")).resolve.flatMap { check =>
          check.withContext(Contexts.AccountListAlbumsContext(authenticated = Some(true))).run
        },
        5.seconds
      )
      println(s"    listAlbums(account-1) = ${if result1.isRight then "ALLOWED" else "DENIED"} (admin access)")
    }

    {
      given CedarSession[Future] = sessionFor(charlie)

      println(s"\n  As Charlie (not a member of account-1):")

      val result1 = Await.result(
        PhotoFlash.Account.ListAlbums.on(AccountId("account-1")).resolve.flatMap { check =>
          check.withContext(Contexts.AccountListAlbumsContext(authenticated = Some(true))).run
        },
        5.seconds
      )
      println(s"    listAlbums(account-1) = ${if result1.isRight then "ALLOWED" else "DENIED"}")
    }

    // -------------------------------------------------------------------------
    // Feature 2: Multiple Parent Types (Album in [Account, AlbumFolder])
    // -------------------------------------------------------------------------
    println("\n\nFeature 2: Multiple Parent Types (Album in [Account, AlbumFolder])")
    println("-" * 70)
    println("  Album hierarchy:")
    println("    Account (account-1)")
    println("      ├── Album (album-1) - direct child of Account")
    println("      └── AlbumFolder (folder-1)")
    println("            ├── Album (album-2) - child of AlbumFolder")
    println("            └── Album (album-3) - child of AlbumFolder")

    {
      given CedarSession[Future] = sessionFor(alice)

      println(s"\n  As Alice (owner):")

      // Define upload context with photo metadata
      val uploadContext = Contexts.AlbumUploadPhotoContext(
        authenticated = Some(true),
        photoFileSize = Some(5242880L), // 5MB
        photoFileType = Some("image/jpeg")
      )

      val result1 = Await.result(
        PhotoFlash.Album.UploadPhoto.on(AlbumId("album-1")).resolve.flatMap { check =>
          check.withContext(uploadContext).run
        },
        5.seconds
      )
      println(
        s"    uploadPhoto(album-1) = ${if result1.isRight then "ALLOWED" else "DENIED"} (direct child of Account)"
      )

      val result2 = Await.result(
        PhotoFlash.Album.UploadPhoto.on(AlbumId("album-2")).resolve.flatMap { check =>
          check.withContext(uploadContext).run
        },
        5.seconds
      )
      println(s"    uploadPhoto(album-2) = ${if result2.isRight then "ALLOWED" else "DENIED"} (child of AlbumFolder)")

      val result3 = Await.result(
        PhotoFlash.Album.UploadPhoto.on(AlbumId("album-4")).resolve.flatMap { check =>
          check.withContext(uploadContext).run
        },
        5.seconds
      )
      println(s"    uploadPhoto(album-4) = ${if result3.isRight then "ALLOWED" else "DENIED"} (in nested folder)")
    }

    // -------------------------------------------------------------------------
    // Feature 3: Context Attributes for File Validation
    // -------------------------------------------------------------------------
    println("\n\nFeature 3: Context Attributes for File Validation")
    println("-" * 70)

    {
      given CedarSession[Future] = sessionFor(alice)

      println(s"  As Alice, uploading with different file sizes:")

      // Small file - should be allowed
      val smallFileContext = Contexts.AlbumUploadPhotoContext(
        authenticated = Some(true),
        photoFileSize = Some(1048576L), // 1MB
        photoFileType = Some("image/jpeg")
      )

      val result1 = Await.result(
        PhotoFlash.Album.UploadPhoto.on(AlbumId("album-1")).resolve.flatMap { check =>
          check.withContext(smallFileContext).run
        },
        5.seconds
      )
      println(s"    uploadPhoto(1MB JPEG)  = ${if result1.isRight then "ALLOWED" else "DENIED"}")

      // Large file - should be denied
      val largeFileContext = Contexts.AlbumUploadPhotoContext(
        authenticated = Some(true),
        photoFileSize = Some(20971520L), // 20MB
        photoFileType = Some("image/jpeg")
      )

      val result2 = Await.result(
        PhotoFlash.Album.UploadPhoto.on(AlbumId("album-1")).resolve.flatMap { check =>
          check.withContext(largeFileContext).run
        },
        5.seconds
      )
      println(s"    uploadPhoto(20MB JPEG) = ${if result2.isRight then "ALLOWED" else "DENIED"} (exceeds 10MB limit)")

      // Executable file - should be forbidden
      val executableContext = Contexts.AlbumUploadPhotoContext(
        authenticated = Some(true),
        photoFileSize = Some(1048576L), // 1MB
        photoFileType = Some("application/x-executable")
      )

      val result3 = Await.result(
        PhotoFlash.Album.UploadPhoto.on(AlbumId("album-1")).resolve.flatMap { check =>
          check.withContext(executableContext).run
        },
        5.seconds
      )
      println(s"    uploadPhoto(executable) = ${if result3.isRight then "ALLOWED" else "DENIED"} (forbidden file type)")
    }

    // -------------------------------------------------------------------------
    // Feature 4: Private Album Protection
    // -------------------------------------------------------------------------
    println("\n\nFeature 4: Private Album Protection")
    println("-" * 70)

    {
      given CedarSession[Future] = sessionFor(alice)

      println(s"  As Alice (owner):")

      val viewContext = Contexts.PhotoViewContext(authenticated = Some(true))

      val result1 = Await.result(
        PhotoFlash.Photo.View.on(PhotoId("photo-1")).resolve.flatMap { check =>
          check.withContext(viewContext).run
        },
        5.seconds
      )
      println(s"    viewPhoto(photo-1) = ${if result1.isRight then "ALLOWED" else "DENIED"} (public photo)")

      val result2 = Await.result(
        PhotoFlash.Photo.View.on(PhotoId("photo-2")).resolve.flatMap { check =>
          check.withContext(viewContext).run
        },
        5.seconds
      )
      println(s"    viewPhoto(photo-2) = ${if result2.isRight then "ALLOWED" else "DENIED"} (private photo, owner)")
    }

    {
      given CedarSession[Future] = sessionFor(bob)

      println(s"\n  As Bob (admin of account-1):")

      val viewContext = Contexts.PhotoViewContext(authenticated = Some(true))

      val result1 = Await.result(
        PhotoFlash.Photo.View.on(PhotoId("photo-2")).resolve.flatMap { check =>
          check.withContext(viewContext).run
        },
        5.seconds
      )
      println(s"    viewPhoto(photo-2) = ${if result1.isRight then "ALLOWED" else "DENIED"} (private photo, admin)")
    }

    {
      given CedarSession[Future] = sessionFor(charlie)

      println(s"\n  As Charlie (different account):")

      val viewContext = Contexts.PhotoViewContext(authenticated = Some(true))

      val result1 = Await.result(
        PhotoFlash.Photo.View.on(PhotoId("photo-1")).resolve.flatMap { check =>
          check.withContext(viewContext).run
        },
        5.seconds
      )
      println(s"    viewPhoto(photo-1) = ${if result1.isRight then "ALLOWED" else "DENIED"} (public photo)")

      val result2 = Await.result(
        PhotoFlash.Photo.View.on(PhotoId("photo-2")).resolve.flatMap { check =>
          check.withContext(viewContext).run
        },
        5.seconds
      )
      println(
        s"    viewPhoto(photo-2) = ${if result2.isRight then "ALLOWED" else "DENIED"} (private photo, not owner/admin)"
      )
    }

    // -------------------------------------------------------------------------
    // Feature 5: Photos in Albums with Multiple Parent Types
    // -------------------------------------------------------------------------
    println("\n\nFeature 5: Photos in Albums (Hierarchical Structure)")
    println("-" * 70)

    {
      given CedarSession[Future] = sessionFor(alice)

      println(s"  As Alice, viewing photos in different album locations:")

      val viewContext = Contexts.PhotoViewContext(authenticated = Some(true))

      val result1 = Await.result(
        PhotoFlash.Photo.View.on(PhotoId("photo-3")).resolve.flatMap { check =>
          check.withContext(viewContext).run
        },
        5.seconds
      )
      println(s"    viewPhoto(photo-3) = ${if result1.isRight then "ALLOWED" else "DENIED"} (in album -> folder)")

      val result2 = Await.result(
        PhotoFlash.Photo.View.on(PhotoId("photo-4")).resolve.flatMap { check =>
          check.withContext(viewContext).run
        },
        5.seconds
      )
      println(
        s"    viewPhoto(photo-4) = ${if result2.isRight then "ALLOWED" else "DENIED"} (in album -> nested folder)"
      )
    }

    println("\n=== Done ===")
  }
}
