---
sidebar_label: Quickstart
title: Quickstart
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Quickstart

This page gets you running quickly with minimal explanation. See
[Installation](./03-installation.md) for detailed setup options.

## Prerequisites

- Scala 2.13 or 3.x
- sbt 1.9+

## 1. Add the Plugin

```scala title="project/plugins.sbt"
addSbtPlugin("io.github.devnico" % "sbt-cedar4s" % "{{VERSION}}")
```

## 2. Configure Build

```scala title="build.sbt"
enablePlugins(Cedar4sPlugin)

cedarSchemaFile := baseDirectory.value / "src/main/resources/schema.cedarschema"
cedarScalaPackage := "com.example.cedar"

libraryDependencies ++= Seq(
  "io.github.devnico" %% "cedar4s-core" % "{{VERSION}}",
  "io.github.devnico" %% "cedar4s-client" % "{{VERSION}}"
)
```

## 3. Define Schema

```cedar title="src/main/resources/schema.cedarschema"
namespace DocShare {
  entity User = {};

  entity Folder = {};

  entity Document in [Folder] = {};

  action "Document::view" appliesTo {
    principal: [User],
    resource: [Document]
  };
}
```

## 4. Create Policy

```cedar title="src/main/resources/policies/main.cedar"
permit (
  principal,
  action == DocShare::Action::"Document::view",
  resource
) when {
  resource in principal.folders
};
```

## 5. Implement Entity Fetchers

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala title="EntityFetchers.scala"
import cedar4s.entities.EntityFetcher
import com.example.cedar.DocShare
import com.example.cedar.EntityIds.{DocumentId, FolderId, UserId}
import scala.concurrent.Future

class DocumentFetcher(db: Database)(using ExecutionContext)
    extends EntityFetcher[Future, DocShare.Entity.Document, DocumentId] {

  def fetch(id: DocumentId): Future[Option[DocShare.Entity.Document]] =
    db.findDocument(id.value).map(_.map { doc =>
      DocShare.Entity.Document(
        id = DocumentId(doc.id),
        folderId = FolderId(doc.folderId)
      )
    })
}

class UserFetcher(db: Database)(using ExecutionContext)
    extends EntityFetcher[Future, DocShare.Entity.User, UserId] {

  def fetch(id: UserId): Future[Option[DocShare.Entity.User]] =
    db.findUser(id.value).map(_.map { user =>
      DocShare.Entity.User(id = UserId(user.id))
    })
}
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala title="EntityFetchers.scala"
import cedar4s.entities.EntityFetcher
import com.example.cedar.DocShare
import com.example.cedar.EntityIds.{DocumentId, FolderId, UserId}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext

class DocumentFetcher(db: Database)(implicit ec: ExecutionContext)
    extends EntityFetcher[Future, DocShare.Entity.Document, DocumentId] {

  def fetch(id: DocumentId): Future[Option[DocShare.Entity.Document]] =
    db.findDocument(id.value).map(_.map { doc =>
      DocShare.Entity.Document(
        id = DocumentId(doc.id),
        folderId = FolderId(doc.folderId)
      )
    })
}

class UserFetcher(db: Database)(implicit ec: ExecutionContext)
    extends EntityFetcher[Future, DocShare.Entity.User, UserId] {

  def fetch(id: UserId): Future[Option[DocShare.Entity.User]] =
    db.findUser(id.value).map(_.map { user =>
      DocShare.Entity.User(id = UserId(user.id))
    })
}
```

</TabItem>
</Tabs>

## 6. Set Up Authorization

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala title="Main.scala"
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import cedar4s.capability.instances.{futureSync, futureMonadError}
import cedar4s.auth.{CedarSession, FlatMap, Principal}
import cedar4s.entities.EntityStore
import cedar4s.client.{CedarEngine, CedarRuntime}
import com.example.cedar.DocShare
import com.example.cedar.EntityIds.*

given FlatMap[Future] = FlatMap.futureInstance

// Register all entity fetchers
val store = EntityStore.builder[Future]()
  .register[DocShare.Entity.Document, DocumentId](DocumentFetcher(db))
  .register[DocShare.Entity.User, UserId](UserFetcher(db))
  .build()

val engine = CedarEngine.fromResources[Future]("policies", Seq("main.cedar"))

// Principal resolver - returns the principal entity, not CedarPrincipal
def resolvePrincipal(p: Principal): Future[Option[DocShare.Entity.User]] = {
  p match {
    case DocShare.Principal.User(userId) =>
      // Load user from database
      db.findUser(userId.value).map(_.map { user =>
        DocShare.Entity.User(id = userId)
      })
    case _ => Future.successful(None)
  }
}

// Create runtime with explicit principal type
val runtime = CedarRuntime[Future, DocShare.Entity.User](
  engine,
  store,
  CedarRuntime.resolverFrom(resolvePrincipal)
)

// Create a session for the current user
given session: CedarSession[Future] =
  runtime.session(DocShare.Principal.User(UserId(currentUserId)))
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala title="Main.scala"
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import cedar4s.capability.instances.{futureSync, futureMonadError}
import cedar4s.auth.{CedarSession, FlatMap, Principal}
import cedar4s.entities.EntityStore
import cedar4s.client.{CedarEngine, CedarRuntime}
import com.example.cedar.DocShare
import com.example.cedar.EntityIds._

implicit val flatMapFuture: FlatMap[Future] = FlatMap.futureInstance

// Register all entity fetchers
val store = EntityStore.builder[Future]()
  .register[DocShare.Entity.Document, DocumentId](DocumentFetcher(db))
  .register[DocShare.Entity.User, UserId](UserFetcher(db))
  .build()

val engine = CedarEngine.fromResources[Future]("policies", Seq("main.cedar"))

// Principal resolver - returns the principal entity, not CedarPrincipal
def resolvePrincipal(p: Principal): Future[Option[DocShare.Entity.User]] = {
  p match {
    case DocShare.Principal.User(userId) =>
      // Load user from database
      db.findUser(userId.value).map(_.map { user =>
        DocShare.Entity.User(id = userId)
      })
    case _ => Future.successful(None)
  }
}

// Create runtime with explicit principal type
val runtime = CedarRuntime[Future, DocShare.Entity.User](
  engine,
  store,
  CedarRuntime.resolverFrom(resolvePrincipal)
)

// Create a session for the current user
implicit val session: CedarSession[Future] =
  runtime.session(DocShare.Principal.User(UserId(currentUserId)))
```

</TabItem>
</Tabs>

## 7. Use the DSL

```scala
import com.example.cedar.DocShare
import com.example.cedar.EntityIds.*

// Check authorization - throws on denial
DocShare.Document.View.on(DocumentId("doc-123")).require

// Check without throwing
DocShare.Document.View.on(DocumentId("doc-123")).isAllowed  // Future[Boolean]

// Get result with error details
DocShare.Document.View.on(DocumentId("doc-123")).run  // Future[Either[CedarAuthError, Unit]]
```

Run `sbt compile` to generate code, then build your application.

