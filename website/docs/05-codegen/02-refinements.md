---
sidebar_label: Typed IDs
title: Typed Entity IDs
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Typed Entity IDs

cedar4s automatically generates typed IDs (newtypes) for every entity, bringing
compile-time type safety to entity identifiers.

## Overview

cedar4s always generates typed ID wrappers for all entities. These IDs are distinct
types at compile-time but have zero runtime overhead.

**Example schema:**

```cedar
namespace DocShare {
  entity User;
  entity Folder;
  entity Document in [Folder];
}
```

**Generated ID types:**

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
package docshare.cedar

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
type UserId = EntityIds.UserId.Type
type FolderId = EntityIds.FolderId.Type
type DocumentId = EntityIds.DocumentId.Type
```

The `Newtype` base class uses Scala 3's opaque types for zero runtime overhead.

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
package docshare.cedar

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
type UserId = EntityIds.UserId.Type
type FolderId = EntityIds.FolderId.Type
type DocumentId = EntityIds.DocumentId.Type
```

In Scala 2, `Newtype` uses type aliases for source compatibility with Scala 3.

</TabItem>
</Tabs>

## Using Typed IDs

### Creating ID Instances

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
import docshare.cedar.*

// Create typed IDs
val userId: UserId = UserId("user-123")
val folderId: FolderId = FolderId("folder-456")
val docId: DocumentId = DocumentId("doc-789")

// Extract underlying string
val rawId: String = userId.value

// Use in authorization checks
Document.Read.on(docId).require
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
import docshare.cedar._

// Create typed IDs
val userId: UserId = UserId("user-123")
val folderId: FolderId = FolderId("folder-456")
val docId: DocumentId = DocumentId("doc-789")

// Extract underlying string
import EntityIds.UserId._
val rawId: String = userId.value

// Use in authorization checks
Document.Read.on(docId).require
```

</TabItem>
</Tabs>

### Type Safety Benefits

The typed IDs prevent mixing up entity IDs at compile time:

```scala
val userId = UserId("user-123")
val docId = DocumentId("doc-456")

// Compile error: type mismatch
// Document.Read.on(userId)  // Won't compile!

// Correct usage
Document.Read.on(docId)  // Compiles
```

## EntityFetcher with Typed IDs

`EntityFetcher` uses the generated typed IDs for type safety:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
import docshare.cedar.*
import docshare.cedar.DocShare.Entity
import scala.concurrent.{ExecutionContext, Future}

class DocumentFetcher(db: Database)(using ExecutionContext)
    extends EntityFetcher[Future, Entity.Document, DocumentId] {

  def fetch(id: DocumentId): Future[Option[Entity.Document]] =
    db.findDocument(id.value).map(_.map { doc =>
      Entity.Document(
        id = DocumentId(doc.id),
        folderId = FolderId(doc.folderId)
      )
    })

  override def fetchBatch(ids: Set[DocumentId])(using Applicative[Future]) =
    db.findDocuments(ids.map(_.value)).map { docs =>
      docs.map(d => DocumentId(d.id) -> convertToEntity(d)).toMap
    }
}
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
import docshare.cedar._
import docshare.cedar.DocShare.Entity
import scala.concurrent.{ExecutionContext, Future}

class DocumentFetcher(db: Database)(implicit ec: ExecutionContext)
    extends EntityFetcher[Future, Entity.Document, DocumentId] {

  def fetch(id: DocumentId): Future[Option[Entity.Document]] =
    db.findDocument(id.value).map(_.map { doc =>
      Entity.Document(
        id = DocumentId(doc.id),
        folderId = FolderId(doc.folderId)
      )
    })

  override def fetchBatch(ids: Set[DocumentId])(implicit F: Applicative[Future]) =
    db.findDocuments(ids.map(_.value)).map { docs =>
      docs.map(d => DocumentId(d.id) -> convertToEntity(d)).toMap
    }
}
```

</TabItem>
</Tabs>

## Entity Hierarchies with Typed IDs

Typed IDs work correctly through entity hierarchies:

```cedar
namespace MultiTenant {
  entity Organization;
  entity Team in [Organization];
  entity Project in [Team];
}
```

Generated entity classes use typed IDs for all relationships:

```scala
case class Organization(
  id: OrganizationId
) extends Entity

case class Team(
  id: TeamId,
  organizationId: OrganizationId  // Parent reference is typed
) extends Entity

case class Project(
  id: ProjectId,
  teamId: TeamId  // Parent reference is typed
) extends Entity
```

## Conversion with Bijection

Each generated ID type includes a `Bijection` for bidirectional conversion:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
import docshare.cedar.*
import cedar4s.Bijection

// The bijection is automatically available
val bij: Bijection[String, UserId] = UserId.bijection

// Convert string to typed ID
val userId: UserId = bij.to("user-123")

// Convert typed ID to string
val str: String = bij.from(userId)

// Or use the shorthand methods
val userId2: UserId = UserId("user-456")
val str2: String = userId2.value
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
import docshare.cedar._
import cedar4s.Bijection

// The bijection is automatically available
val bij: Bijection[String, UserId] = UserId.bijection

// Convert string to typed ID
val userId: UserId = bij.to("user-123")

// Convert typed ID to string
val str: String = bij.from(userId)

// Or use the shorthand methods
val userId2: UserId = UserId("user-456")
import EntityIds.UserId._
val str2: String = userId2.value
```

</TabItem>
</Tabs>

## Runtime Overhead

### Scala 3: Zero-Cost Abstraction

In Scala 3, the generated newtypes use opaque types, which have **zero runtime
overhead**. The typed IDs exist only at compile-time and are erased to raw strings at
runtime.

### Scala 2: Type Aliases

In Scala 2, the `Newtype` implementation uses type aliases (`type UserId = String`),
which also have zero runtime overhead but provide less type safety than Scala 3's
opaque types.

## Best Practices

### Import ID Types

Import the generated ID types at package level:

```scala
import docshare.cedar.*  // Imports all generated types including IDs
```

### Pattern Matching

You can pattern match on typed IDs:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
userId match {
  case UserId(rawId) => println(s"User ID: $rawId")
}
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
userId match {
  case UserId(rawId) => println(s"User ID: $rawId")
}
```

</TabItem>
</Tabs>

