---
sidebar_label: Authorization DSL
title: Authorization DSL
---

# Authorization DSL

cedar4s provides a type-safe DSL for authorization checks via `AuthCheck` and `CedarSession`.

## Overview

Authorization checks are values of type `AuthCheck[P, A, R]`:

- `P` - Principal type (who is asking)
- `A` - Action type (what they want to do)
- `R` - Resource type (on which resource)

These values are created by generated code and executed by a `CedarSession[F]`.

## Creating Authorization Checks

Use the generated resource-centric DSL with typed entity IDs:

```scala
import myapp.cedar.MyApp
import myapp.cedar.EntityIds.*

// All authorization checks use the .on(id) pattern
val check = MyApp.Document.View.on(DocumentId("doc-123"))
// Type: DeferredAuthCheck[F[_], DocumentId, Actions.Document.DocumentAction, Resource[...]]

// For root resources (no parents)
val check = MyApp.Folder.View.on(FolderId("folder-1"))

// Container actions (create/list) go on the parent entity
val check = MyApp.Folder.DocumentCreate.on(FolderId("folder-1"))
```

The `.on(id)` pattern automatically resolves the entity's parent hierarchy via the `EntityStore`.

## Executing Checks

Three execution methods, all require `CedarSession[F]` in scope:

```scala
given CedarSession[Future] = runtime.session(currentUser)

// 1. run - Returns Either, doesn't throw
val result: Future[Either[CedarAuthError, Unit]] = check.run
result.map {
  case Right(()) => println("Authorized!")
  case Left(CedarAuthError.Unauthorized(msg)) => println(s"Denied: $msg")
}

// 2. require - Throws on denial
val allowed: Future[Unit] = check.require
// Throws CedarAuthError on denial

// 3. isAllowed - Returns Boolean
val permitted: Future[Boolean] = check.isAllowed
// Never throws, returns false on denial
```

:::warning Deferred Checks Require FlatMap
When using the `.on(id)` syntax (deferred checks), you must have a `FlatMap[F]` instance in implicit scope. This is required for the entity resolution to work.

```scala
// For scala.concurrent.Future
given FlatMap[Future] = FlatMap.futureInstance

// For Cats Effect IO
import cats.Monad
given FlatMap[IO] = new FlatMap[IO] {
  def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] = fa.flatMap(f)
  def map[A, B](fa: IO[A])(f: A => B): IO[B] = fa.map(f)
  def pure[A](a: A): IO[A] = IO.pure(a)
}
```

Without `FlatMap[F]` in scope, you'll get a compile error when using `.on(id)`.
:::

### When to Use Each

| Method       | Use Case                                                                             |
| ------------ | ------------------------------------------------------------------------------------ |
| `.run`       | Need explicit error handling (custom responses, logging denials, fallback logic)     |
| `.require`   | Fail-fast endpoints where unauthorized access should throw (API routes, controllers) |
| `.isAllowed` | Conditional backend logic (filtering, optional features, audit logging)              |

## Error Handling

Authorization errors are typed as `CedarAuthError`:

```scala
check.run.map {
  case Right(()) => 
    Ok("Success")
  
  case Left(CedarAuthError.Unauthorized(msg)) =>
    Forbidden(s"Access denied: $msg")
  
  case Left(CedarAuthError.AuthorizationFailed(msg, cause)) =>
    InternalServerError("Authorization check failed")
}
```

## Request Modifiers

Authorization checks support several modifiers:

### Context Attributes

Add additional request context:

```scala
import myapp.cedar.Contexts.*

MyApp.Document.View.on(DocumentId("doc-123"))
  .withContext(ViewContext(requestTime = Instant.now()))
  .require
```

Context is merged with existing context. The merge order is:

1. Session-level context from `CedarSession.context` (lowest precedence)
2. Check-level context from `.withContext(...)` (highest precedence)

Check context values override session context values with the same key.

### Entity Attributes vs Context Attributes

Cedar policies can access data from two sources:

**Entity Attributes** - Data stored on entities, accessed via `resource.*` or `principal.*` in policies:

```cedar
// Entity attributes are part of the entity definition
entity Document in [Folder] {
  locked: Bool,
  owner: User,
  classification: String
}

// Accessed in policies via resource
forbid(principal, action == "edit", resource)
  when { resource.locked };

permit(principal, action == "delete", resource)
  when { resource.owner == principal };
```

Entity attributes are:
- Stored in your database and loaded by EntityFetchers
- Part of the entity's permanent data model
- Accessible to all policies for that entity type
- Good for: ownership, status flags, relationships, metadata

**Context Attributes** - Request-specific data, accessed via `context.*` in policies:

```scala
// Context attributes are provided per-request
MyApp.Document.View.on(DocumentId("doc-123"))
  .withContext(ViewContext(
    requestTime = Instant.now(),
    sourceIP = "192.168.1.1"
  ))
  .require
```

```cedar
// Accessed in policies via context
permit(principal, action == "view", resource)
  when { context.requestTime < resource.expirationTime };

forbid(principal, action, resource)
  when { context.sourceIP in ["blocked-ip-list"] };
```

Context attributes are:
- Provided per-authorization request
- Request-specific (time, IP, user agent, etc.)
- Not persisted with entities
- Good for: timestamps, network info, request metadata, temporary flags

### Principal Override

Override the session principal explicitly:

```scala
import myapp.cedar.Principals.*
import myapp.cedar.EntityIds.*
import myapp.cedar.PrincipalEvidence.given

// Use a different principal for this check
MyApp.Document.View.on(DocumentId("doc-123"))
  .asPrincipal(ServiceAccount(ServiceAccountId("ci-bot")))
  .require
```

Requires compile-time evidence that the principal type can perform the action (generated `CanPerform[P, A]` instances).

### Conditional Checks

Only run the check if a condition is true:

```scala
// Only check in production
MyApp.Deployment.Create.on(EnvironmentId("prod"))
  .when(environment.name == "production")
  .require
```

If the condition is false, the check is skipped and succeeds automatically.

## Querying Allowed Actions

Sometimes you need to know which actions a principal is allowed to perform on a resource, rather than checking a specific action. `CedarSession` provides methods to query all allowed actions:

### Using Session Principal

`getAllowedActions` checks which actions the session principal can perform:

```scala
import myapp.cedar.MyApp
import myapp.cedar.EntityIds.*

given session: CedarSession[Future] = runtime.session(currentUser)

// Get all allowed actions for the session principal
val allowedActions: Future[Set[String]] = session.getAllowedActions(
  resource = MyApp.Document("folder-1", "doc-123"),
  actionType = "MyApp::Action",
  allActions = Set("read", "write", "delete", "share")
)

// Returns something like: Set("read", "write")
```

### Using Explicit Principal

`getAllowedActionsFor` checks which actions a specific principal can perform:

```scala
import myapp.cedar.Principals.*

// Check what a different principal can do
val bobActions: Future[Set[String]] = session.getAllowedActionsFor(
  principal = User(UserId("bob")),
  resource = MyApp.Document("folder-1", "doc-123"),
  actionType = "MyApp::Action",
  allActions = Set("read", "write", "delete", "share")
)
```

### Use Cases

**1. Frontend Capability Filtering**

Hide UI elements the user cannot access:

```scala
for {
  actions <- session.getAllowedActions(
    resource = MyApp.Document("folder-1", "doc-123"),
    actionType = "MyApp::Action",
    allActions = Set("read", "write", "delete", "share")
  )
} yield DocumentView(
  showEditButton = actions.contains("write"),
  showDeleteButton = actions.contains("delete"),
  showShareButton = actions.contains("share")
)
```

**2. Permission Indicators**

Show permission status to users:

```scala
val documents: Seq[Document] = loadDocuments()

val withPermissions = documents.traverse { doc =>
  for {
    actions <- session.getAllowedActions(
      resource = MyApp.Document(doc.folderId, doc.id),
      actionType = "MyApp::Action",
      allActions = Set("read", "write", "delete")
    )
  } yield DocumentWithPermissions(
    doc,
    canRead = actions.contains("read"),
    canWrite = actions.contains("write"),
    canDelete = actions.contains("delete")
  )
}
```

**3. Admin Panel - User Permissions**

Check what another user can do (admin feature):

```scala
// Admin checking what Bob can access
for {
  bobActions <- session.getAllowedActionsFor(
    principal = User(UserId("bob")),
    resource = MyApp.Document("folder-1", "doc-123"),
    actionType = "MyApp::Action",
    allActions = Set("read", "write", "delete")
  )
} yield PermissionReport(user = "bob", permissions = bobActions)
```

## Integration Examples

### http4s

```scala
import org.http4s.*
import cats.effect.IO

val routes: AuthedRoutes[CedarSession[IO], IO] = AuthedRoutes.of {
  case GET -> Root / "documents" / docId as session =>
    given CedarSession[IO] = session
    for {
      _ <- MyApp.Document.View.on(DocumentId(docId)).require
      doc <- documentService.get(docId)
    } yield Ok(doc)
}
```

### Play Framework

```scala
class DocumentController(
    runtime: CedarRuntime[Future],
    cc: ControllerComponents
)(using store: EntityStore[Future]) extends AbstractController(cc) {
  
  def getDocument(docId: String) = Action.async { request =>
    given CedarSession[Future] = runtime.session(Principals.User(UserId(request.user.id)))
    
    for {
      _ <- MyApp.Document.View.on(DocumentId(docId)).require
      doc <- documentService.get(docId)
    } yield Ok(Json.toJson(doc))
  }
}
```

