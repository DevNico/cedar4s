---
sidebar_label: Generated Code
title: Generated Code
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Generated Code

cedar4s generates type-safe Scala code from your Cedar schema.

## What Gets Generated

From a Cedar schema like this:

```cedar
namespace DocShare {
  entity User {}
  entity Folder {}
  entity Document in [Folder] {
    name: String,
    owner: User,
    locked: Bool
  }
  
  action "Folder::Read" appliesTo {
    principal: [User],
    resource: Folder
  };
  
  action "Document::Read" appliesTo {
    principal: [User],
    resource: Document
  };
  
  action "Document::Write" appliesTo {
    principal: [User],
    resource: Document
  };
}
```

cedar4s generates:

```
target/scala-*/src_managed/main/
└── docshare/cedar/
    ├── DocShare.scala      # Main DSL with nested Actions, Principal, Resource, Entity
    ├── EntityIds.scala     # Typed ID newtypes for all entities
    ├── CommonTypes.scala   # Custom type aliases / records
    ├── Contexts.scala      # Context types for actions
    ├── EntityFetchers.scala # Fetcher factory helpers
    ├── EntitySchema.scala  # Entity type definitions
    ├── HasParentEvidence.scala # Parent relationship evidence
    ├── PolicyDomain.scala  # Policy domain definitions
    ├── Predicates.scala    # Predicate extension methods
    └── PrincipalEvidence.scala # CanPerform evidence
```

## Generated Files

### EntityIds.scala - Typed ID Types

cedar4s **automatically** generates typed IDs for **every** entity using the
`Newtype` abstraction. This provides compile-time type safety and is always enabled:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

Newtype wrappers for each entity (zero-cost at runtime via opaque types):

```scala
package docshare.cedar

import cedar4s.{Bijection, Newtype}

/**
 * Entity ID newtypes for type-safe ID handling.
 *
 * Each entity type has a corresponding ID newtype that wraps String
 * with zero runtime overhead. This prevents accidentally mixing up
 * IDs from different entity types.
 */
object EntityIds {
  /** ID type for User entities */
  object UserId extends Newtype[String]
  type UserId = UserId.Type

  /** ID type for Folder entities */
  object FolderId extends Newtype[String]
  type FolderId = FolderId.Type

  /** ID type for Document entities */
  object DocumentId extends Newtype[String]
  type DocumentId = DocumentId.Type
}

// Re-exported at package level for convenience
type UserId = EntityIds.UserId
type FolderId = EntityIds.FolderId
type DocumentId = EntityIds.DocumentId
```

Usage example:

```scala
import docshare.cedar.EntityIds._

// Create typed IDs
val userId: UserId = UserId("user-123")
val docId: DocumentId = DocumentId("doc-456")

// Extract underlying string
val str: String = userId.value

// Pattern matching
userId match {
  case UserId(s) => println(s"User: $s")
}

// Use the bijection for conversions
val bij: Bijection[String, UserId] = UserId.bijection
```

The `Newtype` abstraction provides:
- `apply(value: String): Type` - wrap a String
- `extension (id: Type) def value: String` - unwrap to String
- `unapply(id: Type): Some[String]` - pattern matching
- `bijection: Bijection[String, Type]` - bidirectional conversion

In Scala 3, `Type` is an opaque type alias, providing zero-cost abstraction.

</TabItem>
<TabItem value="scala2" label="Scala 2">

Newtype wrappers for each entity (type alias in Scala 2):

```scala
package docshare.cedar

import cedar4s.{Bijection, Newtype}

/**
 * Entity ID newtypes for type-safe ID handling.
 *
 * Each entity type has a corresponding ID newtype that wraps String.
 * In Scala 2, Type is a plain type alias for source compatibility.
 */
object EntityIds {
  /** ID type for User entities */
  object UserId extends Newtype[String]
  type UserId = UserId.Type

  /** ID type for Folder entities */
  object FolderId extends Newtype[String]
  type FolderId = FolderId.Type

  /** ID type for Document entities */
  object DocumentId extends Newtype[String]
  type DocumentId = DocumentId.Type
}

// Re-exported at package level for convenience
type UserId = EntityIds.UserId
type FolderId = EntityIds.FolderId
type DocumentId = EntityIds.DocumentId
```

Usage example:

```scala
import docshare.cedar.EntityIds._

// Create typed IDs
val userId: UserId = UserId("user-123")
val docId: DocumentId = DocumentId("doc-456")

// Extract underlying string (via implicit TypeOps)
val str: String = userId.value

// Pattern matching
userId match {
  case UserId(s) => println(s"User: $s")
}

// Use the bijection for conversions
val bij: Bijection[String, UserId] = UserId.bijection
```

The `Newtype` abstraction provides:
- `apply(value: String): Type` - wrap a String
- `implicit class TypeOps` with `.value: String` - unwrap to String
- `unapply(id: Type): Some[String]` - pattern matching
- `bijection: Bijection[String, Type]` - bidirectional conversion

Note: In Scala 2, `Type` is just a type alias (`Type = String`), so the type safety
is weaker than Scala 3's opaque types. The Newtype pattern is used for source
compatibility with Scala 3.

</TabItem>
</Tabs>

### DocShare.Entity - Entity Case Classes

Entity case classes are **nested within** the main DSL object (`DocShare.Entity`). All entities are defined inside this namespace to avoid collision with action names. Each entity has:
- A case class with typed IDs for the entity itself and references to other entities
- An implicit `CedarEntityType` instance for conversion to Cedar entities

```scala
package docshare.cedar

object DocShare {
  object Entity {
    final case class User(
      id: UserId,
      email: String,
      name: String
    )

    object User {
      implicit val cedarEntityType: CedarEntityType.Aux[User, UserId] =
        new CedarEntityType[User] {
          type Id = UserId
          val entityType: String = "DocShare::User"

          def toCedarEntity(a: User): CedarEntity = CedarEntity(
            entityType = entityType,
            entityId = a.id.value,
            parents = Set.empty,
            attributes = Map(
              "email" -> CedarValue.string(a.email),
              "name" -> CedarValue.string(a.name)
            )
          )

          def getParentIds(a: User): List[(String, String)] = Nil
        }
    }

    final case class Document(
      id: DocumentId,
      folderId: FolderId,  // Parent reference (typed)
      name: String,
      owner: String,       // Entity reference (String ID, not UserId)
      editors: Set[String],
      viewers: Set[String],
      locked: Boolean
    )

    object Document {
      implicit val cedarEntityType: CedarEntityType.Aux[Document, DocumentId] =
        new CedarEntityType[Document] {
          type Id = DocumentId
          val entityType: String = "DocShare::Document"

          def toCedarEntity(a: Document): CedarEntity = CedarEntity(
            entityType = entityType,
            entityId = a.id.value,
            parents = Set(CedarEntityUid("DocShare::Folder", a.folderId.value)),
            attributes = Map(
              "name" -> CedarValue.string(a.name),
              "owner" -> CedarValue.entity("DocShare::User", a.owner),
              "editors" -> CedarValue.entitySet(a.editors, "DocShare::User"),
              "viewers" -> CedarValue.entitySet(a.viewers, "DocShare::User"),
              "locked" -> CedarValue.bool(a.locked)
            )
          )

          def getParentIds(a: Document): List[(String, String)] =
            List("DocShare::Folder" -> a.folderId.value)
        }
    }
  }
}
```

Key features:
- **All entities are nested** in `DocShare.Entity`, not a separate `Entities.scala` file
- Entity references (like `owner`) are plain Strings, not typed IDs
- Parent references (like `folderId`) use typed IDs from `EntityIds`
- Companion objects provide `CedarEntityType` instances for conversion

### DocShare.Actions - Action Objects

Action objects are **nested within** the main DSL (`DocShare.Actions`). All actions are defined inside this namespace to avoid collision with entity names. Each action:
- Is a case object extending a domain-specific sealed trait
- Has phantom types for the policy domain and ownership classification
- Extends `CedarAction` for compatibility with the auth DSL

```scala
package docshare.cedar

object DocShare {
  object Actions {
    sealed trait Action extends CedarAction {
      type Domain <: PolicyDomain
      type Ownership
      def value: String = name
      def domain: PolicyDomain
    }

    object Folder {
      sealed trait FolderAction extends Action {
        type Domain = PolicyDomain.Folder.type
        type Ownership = Resource.RootOwnership
        val domain: PolicyDomain = PolicyDomain.Folder
        def cedarAction: String = s"""DocShare::Action::"Folder::$name""""
      }

      case object View extends FolderAction {
        val name = "view"
        val isCollectionAction = false
      }

      case object Edit extends FolderAction {
        val name = "edit"
        val isCollectionAction = false
      }

      val all: Set[FolderAction] = Set(View, Edit)
    }

    object Document {
      sealed trait DocumentAction extends Action {
        type Domain = PolicyDomain.Document.type
        type Ownership = Resource.DirectOwnership
        val domain: PolicyDomain = PolicyDomain.Document
        def cedarAction: String = s"""DocShare::Action::"Document::$name""""
      }

      case object View extends DocumentAction {
        val name = "view"
        val isCollectionAction = false
      }

      case object Edit extends DocumentAction {
        val name = "edit"
        val isCollectionAction = false
      }

      val all: Set[DocumentAction] = Set(View, Edit)
    }

    val all: Set[Action] = Folder.all ++ Document.all
  }
}
```

Key features:
- **All actions are nested** in `DocShare.Actions`, not a separate `Actions.scala` file
- Actions grouped by resource type (Folder, Document)
- Phantom types link actions to compatible resource types
- `cedarAction` method provides the full Cedar action string
- `all` collections for iterating over available actions

### DocShare DSL - Resource-Centric Authorization

The main `DocShare` object contains the resource-centric DSL with `.on(id)` for authorization checks.

#### .on(id) Method

Each action provides an `.on(id)` method that creates a deferred authorization check:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
package docshare.cedar

/**
 * Resource-centric DSL for DocShare authorization.
 *
 * All authorization checks use the `.on(id)` pattern which automatically
 * resolves the resource hierarchy via EntityStore.
 */
object DocShare {

  object Folder {
    object View {
      /**
       * Create a deferred authorization check that resolves via EntityStore.
       *
       * The EntityStore will fetch the Folder entity and resolve
       * its parent hierarchy automatically.
       */
      def on[F[_]](id: FolderId)(
          implicit session: CedarSession[F],
          flatMap: FlatMap[F]
      ): DeferredAuthCheck[F, FolderId, Actions.Folder.FolderAction, Resource.Resource[...]] =
        DeferredAuthCheck(
          entityType = "DocShare::Folder",
          entityId = id,
          actionValue = Actions.Folder.View,
          buildResource = (ref: ResourceRef) => Resource.fromResourceRef[...](ref)
        )
    }

    object Edit {
      def on[F[_]](id: FolderId)(
          implicit session: CedarSession[F],
          flatMap: FlatMap[F]
      ): DeferredAuthCheck[F, FolderId, Actions.Folder.FolderAction, Resource.Resource[...]] = ...
    }
  }

  object Document {
    object View {
      def on[F[_]](id: DocumentId)(
          implicit session: CedarSession[F],
          flatMap: FlatMap[F]
      ): DeferredAuthCheck[F, DocumentId, Actions.Document.DocumentAction, Resource.Resource[...]] = ...
    }

    object Edit {
      def on[F[_]](id: DocumentId)(
          implicit session: CedarSession[F],
          flatMap: FlatMap[F]
      ): DeferredAuthCheck[F, DocumentId, Actions.Document.DocumentAction, Resource.Resource[...]] = ...
    }
  }
}
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

The Scala 2 version is identical - it uses `implicit` instead of `using` for parameters:

```scala
package docshare.cedar

object DocShare {
  object Document {
    object View {
      def on[F[_]](id: DocumentId)(
          implicit session: CedarSession[F],
          flatMap: FlatMap[F]
      ): DeferredAuthCheck[F, DocumentId, Actions.Document.DocumentAction, Resource.Resource[...]] = ...
    }
  }
}
```

</TabItem>
</Tabs>

Usage example:

```scala
import docshare.cedar.DocShare
import docshare.cedar.EntityIds.DocumentId

// Create a deferred auth check
val check = DocShare.Document.View.on(DocumentId("doc-123"))

// Execute it (requires CedarSession in scope)
given session: CedarSession[F] = ...
val result = check.run  // Returns F[Either[CedarAuthError, Unit]]
```

Key features:
- `.on(id)` creates a `DeferredAuthCheck` that resolves the resource hierarchy via `EntityStore`
- Returns effect-polymorphic `F[_]` types
- Requires implicit `CedarSession[F]` (which contains the EntityStore) and `FlatMap[F]`
- Session provides both the principal and access to the EntityStore for entity resolution

#### allowedActionNames Method

Each resource domain provides an `allowedActionNames` method for capability-based authorization:

```scala
object Folder {
  // ... action methods like View.on(id) ...

  /**
   * Get allowed action names for a specific Folder.
   *
   * Returns the names of all actions the current principal can perform
   * on the specified Folder resource.
   *
   * @param id The Folder ID
   * @return F containing the set of allowed action names
   */
  def allowedActionNames[F[_]](id: FolderId)(
      implicit session: CedarSession[F],
      flatMap: FlatMap[F]
  ): F[Set[String]] = {
    // First resolve the resource via EntityStore
    flatMap.flatMap(session.entityStore.loadEntityWithParents("DocShare::Folder", id.value)) {
      case Some((entity, parentIds)) =>
        val resource = DocShare.Resource.fromResourceRef[PolicyDomain.Folder.type, DocShare.Resource.RootOwnership](
          ResourceRef("DocShare::Folder", Some(id.value), parentIds)
        )
        val allActions = Set("Folder::view", "Folder::edit", "Folder::delete")
        session.getAllowedActions(resource, "DocShare::Action", allActions)
      case None =>
        flatMap.pure(Set.empty[String])
    }
  }
}
```

Usage example:

```scala
import docshare.cedar.DocShare
import docshare.cedar.EntityIds.FolderId

given session: CedarSession[F] = ...

// Get all actions the current principal can perform on this folder
val allowedActions: F[Set[String]] =
  DocShare.Folder.allowedActionNames(FolderId("folder-123"))

// Returns e.g., Set("view", "edit") if principal can view and edit but not delete
```

Key features:
- Requires both `CedarSession[F]` (for principal and EntityStore access) and `FlatMap[F]`
- Resolves the resource hierarchy via `session.entityStore.loadEntityWithParents`
- Returns simple action name strings (e.g., "view", "edit") not full Cedar action strings
- Useful for frontend capability checks to show/hide UI elements

### DocShare.Principal - Principal Types

Principal types are **nested within** the main DSL (`DocShare.Principal`). Each principal:
- Is a case class with a typed ID
- Extends the `Principal` base trait (from cedar4s-core)
- Implements entity type and ID methods

```scala
package docshare.cedar

object DocShare {
  object Principal {
    /**
     * User principal type.
     *
     * Entity type: DocShare::User
     */
    final case class User(id: UserId) extends Principal {
      val entityType: String = "DocShare::User"
      val entityId: String = id.value
      override def toCedarEntity: CedarEntityUid =
        CedarEntityUid("DocShare::User", id.value)
    }

    /**
     * Group principal type.
     *
     * Entity type: DocShare::Group
     */
    final case class Group(id: GroupId) extends Principal {
      val entityType: String = "DocShare::Group"
      val entityId: String = id.value
      override def toCedarEntity: CedarEntityUid =
        CedarEntityUid("DocShare::Group", id.value)
    }
  }
}
```

Usage with sessions:

```scala
import docshare.cedar.DocShare
import docshare.cedar.EntityIds.UserId

// Create a principal
val principal = DocShare.Principal.User(UserId("alice"))

// Create a session for this principal
val session = cedarRuntime.session(principal)

// Or use with explicit principal in auth checks
val check = DocShare.Document.View
  .on(DocumentId("doc-123"))
  .asPrincipal(principal)
```

### PrincipalEvidence.scala

Compile-time evidence for principal-action pairs:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
package docshare.cedar

object PrincipalEvidence {
  given CanPerform[Principals.User, Actions.Folder.Read.type] = CanPerform.instance
  given CanPerform[Principals.User, Actions.Document.Read.type] = CanPerform.instance
  given CanPerform[Principals.User, Actions.Document.Write.type] = CanPerform.instance
}
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
package docshare.cedar

object PrincipalEvidence {
  implicit val userCanReadFolder: CanPerform[Principals.User, Actions.Folder.Read.type] = CanPerform.instance
  implicit val userCanReadDocument: CanPerform[Principals.User, Actions.Document.Read.type] = CanPerform.instance
  implicit val userCanWriteDocument: CanPerform[Principals.User, Actions.Document.Write.type] = CanPerform.instance
}
```

</TabItem>
</Tabs>

This enables compile-time checking of `.asPrincipal(...)` calls.

### CommonTypes.scala

Custom types declared with `type` are emitted here:

```scala
package docshare.cedar

type Email = String

final case class Metadata(
  owner: String,
  tags: Set[String]
)
```

## Output Location

Generated code goes to sbt's managed sources directory:

```
target/scala-*/src_managed/main/<package>/cedar/
```

The package is derived from your Cedar namespace (lowercase, dots for segments).

## Regenerating Code

Code regenerates automatically when:

- Cedar schema files change
- You run `sbt compile`

Force regeneration:

```bash
sbt clean compile
```

## Using Generated Code

Import the generated types. Note that **all major types are nested** within the main DSL object:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
import docshare.cedar.DocShare              // Main DSL object
import docshare.cedar.DocShare.{Entity, Actions, Principal, Resource}  // Nested objects
import docshare.cedar.EntityIds.*           // ID types are in separate EntityIds module
import docshare.cedar.PrincipalEvidence.given

// Create authorization checks with typed IDs
DocShare.Document.View.on(DocumentId("doc-1")).require

// Access entities (nested in DocShare.Entity)
val doc: Entity.Document = Entity.Document(
  id = DocumentId("doc-1"),
  folderId = FolderId("folder-1"),
  name = "My Doc",
  owner = "user-1",
  editors = Set.empty,
  viewers = Set.empty,
  locked = false
)

// Access actions (nested in DocShare.Actions)
val action: Actions.Document.DocumentAction = Actions.Document.View

// Create principals with typed IDs (nested in DocShare.Principal)
val principal = Principal.User(UserId("user-1"))
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
import docshare.cedar.DocShare              // Main DSL object
import docshare.cedar.DocShare.{Entity, Actions, Principal, Resource}  // Nested objects
import docshare.cedar.EntityIds._           // ID types are in separate EntityIds module
import docshare.cedar.PrincipalEvidence._

// Create authorization checks with typed IDs
DocShare.Document.View.on(DocumentId("doc-1")).require

// Access entities (nested in DocShare.Entity)
val doc: Entity.Document = Entity.Document(
  id = DocumentId("doc-1"),
  folderId = FolderId("folder-1"),
  name = "My Doc",
  owner = "user-1",
  editors = Set.empty,
  viewers = Set.empty,
  locked = false
)

// Access actions (nested in DocShare.Actions)
val action: Actions.Document.DocumentAction = Actions.Document.View

// Create principals with typed IDs (nested in DocShare.Principal)
val principal = Principal.User(UserId("user-1"))
```

</TabItem>
</Tabs>

### Predicates.scala - Predicate Extension Methods

cedar4s generates predicate extension methods for entity attributes in `Predicates.scala`:

```scala
import docshare.cedar.Predicates._

// Check that document is not locked before editing
DocShare.Document.Edit.on(docId)
  .unlessLocked  // Generated from 'locked: Bool' attribute
  .require

// Check owner matches principal
DocShare.Document.Delete.on(docId)
  .checkOwner  // Generated from 'owner: User' attribute
  .require
```

These predicates work with resource attribute checks at runtime.

