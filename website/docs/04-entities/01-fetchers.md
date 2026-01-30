---
sidebar_label: Entity Fetchers
title: Implementing Entity Fetchers
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Implementing Entity Fetchers

`EntityFetcher[F, A, Id]` loads entities from your data store and converts them to Cedar entities.

## Type Parameters

- `F[_]` - Effect type (Future, IO, etc.)
- `A` - Entity type (generated Cedar entity class)
- `Id` - Entity ID type (typed ID like `DocumentId`, `UserId`, etc.)

## Basic Implementation

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
import cedar4s.entities.EntityFetcher
import scala.concurrent.Future
import scala.concurrent.ExecutionContext

// Import generated Cedar entity types and IDs
import com.example.myapp.cedar.EntityIds.{DocumentId, FolderId, UserId}

class DocumentFetcher(db: Database)(using ec: ExecutionContext)
    extends EntityFetcher[Future, MyApp.Entity.Document, DocumentId] {

  def fetch(id: DocumentId): Future[Option[MyApp.Entity.Document]] =
    db.findDocument(id.value).map(_.map { doc =>
      // Convert domain model to generated Cedar entity
      MyApp.Entity.Document(
        id = DocumentId(doc.id),
        folderId = FolderId(doc.folderId),
        name = doc.name,
        owner = UserId(doc.ownerId),
        editors = doc.editors.map(UserId(_)).toSet,
        locked = doc.locked
      )
    })
}
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
import cedar4s.entities.EntityFetcher
import scala.concurrent.Future
import scala.concurrent.ExecutionContext

// Import generated Cedar entity types and IDs
import com.example.myapp.cedar.EntityIds.{DocumentId, FolderId, UserId}

class DocumentFetcher(db: Database)(implicit ec: ExecutionContext)
    extends EntityFetcher[Future, Entities.Document, DocumentId] {

  def fetch(id: DocumentId): Future[Option[Entities.Document]] =
    db.findDocument(id.value).map(_.map { doc =>
      // Convert domain model to generated Cedar entity
      Entities.Document(
        id = DocumentId(doc.id),
        folderId = FolderId(doc.folderId),
        name = doc.name,
        owner = UserId(doc.ownerId),
        editors = doc.editors.map(UserId(_)).toSet,
        locked = doc.locked
      )
    })
}
```

</TabItem>
</Tabs>

The `fetch` method:

- Takes a typed entity ID
- Returns `F[Option[A]]` - the entity if found, `None` if not
- Converts your domain model to the generated Cedar entity type with typed IDs

## Factory Methods

For simple cases, use factory methods instead of extending the trait:

```scala
// Typed IDs (recommended)
val fetcher = EntityFetcher[Future, MyApp.Entity.User, UserId] { id =>
  db.findUser(id.value).map(_.map(toCedar))
}

// With batch support
val fetcher = EntityFetcher.withBatch[Future, MyApp.Entity.User, UserId](
  f = id => db.findUser(id.value).map(_.map(toCedar)),
  batch = ids => db.findUsers(ids.map(_.value)).map(_.map(u => UserId(u.id) -> toCedar(u)).toMap)
)
```

## Implementing fetchBatch

Override `fetchBatch` for efficient multi-entity loading:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
class DocumentFetcher(db: Database)(using ec: ExecutionContext)
    extends EntityFetcher[Future, MyApp.Entity.Document, DocumentId] {

  def fetch(id: DocumentId): Future[Option[MyApp.Entity.Document]] =
    db.findDocument(id.value).map(_.map(toCedar))

  // Single SQL query for all IDs
  override def fetchBatch(ids: Set[DocumentId])(implicit F: Applicative[Future]): Future[Map[DocumentId, MyApp.Entity.Document]] =
    db.run(documents.filter(_.id.inSet(ids.map(_.value))).result).map { docs =>
      docs.map(d => DocumentId(d.id) -> toCedar(d)).toMap
    }

  private def toCedar(doc: DomainDocument): MyApp.Entity.Document =
    MyApp.Entity.Document(
      id = DocumentId(doc.id),
      folderId = FolderId(doc.folderId),
      name = doc.name,
      owner = UserId(doc.ownerId),
      editors = doc.editors.map(UserId(_)).toSet,
      locked = doc.locked
    )
}
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
class DocumentFetcher(db: Database)(implicit ec: ExecutionContext)
    extends EntityFetcher[Future, MyApp.Entity.Document, DocumentId] {

  def fetch(id: DocumentId): Future[Option[MyApp.Entity.Document]] =
    db.findDocument(id.value).map(_.map(toCedar))

  // Single SQL query for all IDs
  override def fetchBatch(ids: Set[DocumentId])(implicit F: Applicative[Future]): Future[Map[DocumentId, MyApp.Entity.Document]] =
    db.run(documents.filter(_.id.inSet(ids.map(_.value))).result).map { docs =>
      docs.map(d => DocumentId(d.id) -> toCedar(d)).toMap
    }

  private def toCedar(doc: DomainDocument): MyApp.Entity.Document =
    MyApp.Entity.Document(
      id = DocumentId(doc.id),
      folderId = FolderId(doc.folderId),
      name = doc.name,
      owner = UserId(doc.ownerId),
      editors = doc.editors.map(UserId(_)).toSet,
      locked = doc.locked
    )
}
```

</TabItem>
</Tabs>

### Why fetchBatch Matters

Without `fetchBatch`, loading 100 entities requires 100 database queries:

```
SELECT * FROM documents WHERE id = 'doc-1'  -- 5ms
SELECT * FROM documents WHERE id = 'doc-2'  -- 5ms
... (98 more queries)
Total: ~500ms
```

With `fetchBatch`, one query:

```sql
SELECT * FROM documents WHERE id IN ('doc-1', 'doc-2', ...)  -- 5ms
Total: ~5ms (100x faster)
```

## Converting Domain Models

Keep the conversion logic clean and testable:

```scala
class DocumentFetcher(db: Database)(using ec: ExecutionContext)
    extends EntityFetcher[Future, MyApp.Entity.Document, DocumentId] {

  def fetch(id: DocumentId): Future[Option[MyApp.Entity.Document]] =
    db.findDocument(id.value).map(_.map(DocumentConverter.toCedar))

  override def fetchBatch(ids: Set[DocumentId])(using Applicative[Future]) =
    db.findDocuments(ids.map(_.value)).map(_.map(d => DocumentId(d.id) -> DocumentConverter.toCedar(d)).toMap)
}

object DocumentConverter {
  def toCedar(doc: DomainDocument): MyApp.Entity.Document =
    MyApp.Entity.Document(
      id = DocumentId(doc.id),
      folderId = FolderId(doc.folderId),
      name = doc.name,
      owner = UserId(doc.ownerId),
      editors = doc.editors.map(UserId(_)).toSet,
      locked = doc.lockedAt.isDefined
    )
}
```

## Handling Missing Entities

When an entity doesn't exist, return `None`:

```scala
def fetch(id: DocumentId): Future[Option[Entities.Document]] =
  db.findDocument(id.value).map(_.map(toCedar))  // Returns None if not found
```

For `fetchBatch`, simply omit missing entities from the result map:

```scala
override def fetchBatch(ids: Set[DocumentId])(using Applicative[Future]) =
  db.findDocuments(ids.map(_.value)).map { docs =>
    // Only includes documents that exist
    docs.map(d => DocumentId(d.id) -> toCedar(d)).toMap
  }
```

Missing entities in authorization checks result in denial - Cedar requires all
referenced entities to exist.

## Typed IDs

Cedar4s generates typed IDs (newtypes) for type safety. Each entity gets a distinct
ID type that prevents accidentally mixing up different entity types:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
// Generated in EntityIds.scala using Newtype abstraction
import cedar4s.{Bijection, Newtype}

object EntityIds {
  /** ID type for Document entities */
  object DocumentId extends Newtype[String]
  type DocumentId = DocumentId.Type

  /** ID type for Folder entities */
  object FolderId extends Newtype[String]
  type FolderId = FolderId.Type
}

// Usage:
val docId: DocumentId = DocumentId("doc-123")
val str: String = docId.value

// Newtype provides opaque types - zero runtime cost
// with compile-time type safety
```

The `Newtype` base class provides:
- `apply(value: String): Type` - wrap a String
- `extension (id: Type) def value: String` - unwrap to String
- `unapply(id: Type): Some[String]` - pattern matching
- `bijection: Bijection[String, Type]` - bidirectional conversion

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
// Generated in EntityIds.scala using Newtype abstraction
import cedar4s.{Bijection, Newtype}

object EntityIds {
  /** ID type for Document entities */
  object DocumentId extends Newtype[String]
  type DocumentId = DocumentId.Type

  /** ID type for Folder entities */
  object FolderId extends Newtype[String]
  type FolderId = FolderId.Type
}

// Usage:
val docId: DocumentId = DocumentId("doc-123")
val str: String = docId.value

// In Scala 2, Newtype.Type is a type alias (Type = String)
// This provides source compatibility with Scala 3's opaque types
```

The `Newtype` base class provides:
- `apply(value: String): Type` - wrap a String
- `implicit class TypeOps` with `.value: String` - unwrap to String
- `unapply(id: Type): Some[String]` - pattern matching
- `bijection: Bijection[String, Type]` - bidirectional conversion

Note: In Scala 2, the codegen uses `Newtype` for consistency, but the type safety
is weaker (Type is just an alias). Upgrade to Scala 3 for true zero-cost opaque
types.

</TabItem>
</Tabs>

Use typed IDs throughout your fetchers:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
class DocumentFetcher(db: Database)(using ec: ExecutionContext)
    extends EntityFetcher[Future, MyApp.Entity.Document, DocumentId] {

  def fetch(id: DocumentId): Future[Option[MyApp.Entity.Document]] =
    db.findDocument(id.value).map(_.map(toCedar))

  override def fetchBatch(ids: Set[DocumentId])(using Applicative[Future]) =
    db.findDocuments(ids.map(_.value)).map { docs =>
      docs.map(d => DocumentId(d.id) -> toCedar(d)).toMap
    }
}
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
class DocumentFetcher(db: Database)(implicit ec: ExecutionContext)
    extends EntityFetcher[Future, MyApp.Entity.Document, DocumentId] {

  def fetch(id: DocumentId): Future[Option[MyApp.Entity.Document]] =
    db.findDocument(id.value).map(_.map(toCedar))

  override def fetchBatch(ids: Set[DocumentId])(implicit F: Applicative[Future]) =
    db.findDocuments(ids.map(_.value)).map { docs =>
      docs.map(d => DocumentId(d.id) -> toCedar(d)).toMap
    }
}
```

</TabItem>
</Tabs>

This ensures you can't accidentally pass a `UserId` where a `DocumentId` is expected.

