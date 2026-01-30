---
sidebar_label: Frontend Capabilities
title: Frontend Permission Capabilities
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Frontend Permission Capabilities

Expose authorization capabilities to frontend applications for UI permission checks.

## Overview

Frontend applications often need to know what actions a user can perform to:

- Show/hide UI elements (edit buttons, delete icons)
- Enable/disable form controls
- Display appropriate navigation options

cedar4s exposes capabilities directly on resource references in the generated DSL.
When combined with [Smithy generation](../05-codegen/03-smithy-generation.md), you get
end-to-end type safety from Cedar schema to TypeScript frontend.

## Generated Capabilities

Capabilities are computed from the resource DSL:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
// Generated from your Cedar schema
import cedar4s.auth.CapabilitySet
import myapp.cedar.Actions.Document.DocumentAction
import myapp.cedar.MyApp

given session: CedarSession[Future] = runtime.session(currentUser)

// Get typed allowed actions for a document
val allowed: Future[CapabilitySet[DocumentAction]] =
  MyApp.Document.on(folderId, documentId).capabilities

// Or get action names as strings
val allowedNames: Future[Set[String]] =
  MyApp.Document.on(folderId, documentId).capabilities.map(_.names)
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
// Generated from your Cedar schema
import cedar4s.auth.CapabilitySet
import myapp.cedar.Actions.Document.DocumentAction
import myapp.cedar.MyApp

implicit val session: CedarSession[Future] = runtime.session(currentUser)

// Get typed allowed actions for a document
val allowed: Future[CapabilitySet[DocumentAction]] =
  MyApp.Document.on(folderId, documentId).capabilities

// Or get action names as strings
val allowedNames: Future[Set[String]] =
  MyApp.Document.on(folderId, documentId).capabilities.map(_.names)
```

</TabItem>
</Tabs>

`CapabilitySet` provides:

- `allowed` - Typed `Set[DomainAction]`
- `names` - `Set[String]` action names

### Enriching Domain Objects

Use `capabilities` to add allowed actions to existing objects:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
import myapp.cedar.Actions.Document.DocumentAction
import myapp.cedar.MyApp

def getDocumentWithCapabilities(folderId: String, docId: String)(
    using session: CedarSession[Future]
): Future[DocumentResponse] = {
  for {
    doc <- documentService.get(docId)
    caps <- MyApp.Document.on(folderId, docId).capabilities
  } yield DocumentResponse(doc.id, doc.name, caps.allowed)
}
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
import myapp.cedar.Actions.Document.DocumentAction
import myapp.cedar.MyApp

def getDocumentWithCapabilities(folderId: String, docId: String)(
    implicit session: CedarSession[Future]
): Future[DocumentResponse] = {
  for {
    doc <- documentService.get(docId)
    caps <- MyApp.Document.on(folderId, docId).capabilities
  } yield DocumentResponse(doc.id, doc.name, caps.allowed)
}
```

</TabItem>
</Tabs>

## API Endpoint Pattern

Expose capabilities via a dedicated endpoint:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
import myapp.cedar.Actions.Document.DocumentAction
import myapp.cedar.MyApp

class CapabilitiesController(
    runtime: CedarRuntime[Future],
    cc: ControllerComponents
) extends AbstractController(cc) {

  def documentCapabilities(folderId: String, docId: String) = Action.async { request =>
    given CedarSession[Future] = runtime.session(request.user)

    MyApp.Document.on(folderId, docId).capabilities.map { caps =>
      Ok(Json.obj(
        "allowedActions" -> caps.names
      ))
    }
  }
}
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
import myapp.cedar.Actions.Document.DocumentAction
import myapp.cedar.MyApp

class CapabilitiesController(
    runtime: CedarRuntime[Future],
    cc: ControllerComponents
) extends AbstractController(cc) {

  def documentCapabilities(folderId: String, docId: String) = Action.async { request =>
    implicit val session: CedarSession[Future] = runtime.session(request.user)

    MyApp.Document.on(folderId, docId).capabilities.map { caps =>
      Ok(Json.obj(
        "allowedActions" -> caps.names
      ))
    }
  }
}
```

</TabItem>
</Tabs>

## smithy4s Integration

When using [Smithy-generated action enums](../05-codegen/03-smithy-generation.md),
apply the generated mixin to your API types:

```smithy
use com.example.api.authz#DocumentCapabilitiesMixin

structure Document with [DocumentCapabilitiesMixin] {
    @required
    id: String
    @required
    name: String
}
```

Then implement using resource capabilities:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
import myapp.cedar.Actions.Document.DocumentAction
import myapp.api.{DocumentAction => ApiDocumentAction, Document => ApiDocument}

def getDocument(folderId: String, docId: String)(
    using session: CedarSession[IO]
): IO[ApiDocument] = {
  for {
    doc <- documentService.get(docId)
    caps <- MyApp.Document.on(folderId, docId).capabilities
    apiActions = caps.allowed.toList.map(a => ApiDocumentAction.fromString(a.name.toUpperCase))
  } yield ApiDocument(
    id = doc.id,
    name = doc.name,
    allowedActions = apiActions
  )
}
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
import myapp.cedar.Actions.Document.DocumentAction
import myapp.api.{DocumentAction => ApiDocumentAction, Document => ApiDocument}

def getDocument(folderId: String, docId: String)(
    implicit session: CedarSession[IO]
): IO[ApiDocument] = {
  for {
    doc <- documentService.get(docId)
    caps <- MyApp.Document.on(folderId, docId).capabilities
    apiActions = caps.allowed.toList.map(a => ApiDocumentAction.fromString(a.name.toUpperCase))
  } yield ApiDocument(
    id = doc.id,
    name = doc.name,
    allowedActions = apiActions
  )
}
```

</TabItem>
</Tabs>

## Bulk Capabilities

For list views, compute capabilities for multiple resources:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
import myapp.cedar.Actions.Document.DocumentAction
import myapp.cedar.MyApp

def listDocumentsWithCapabilities(folderId: String)(
    using session: CedarSession[Future]
): Future[Seq[DocumentListItem]] = {
  for {
    docs <- documentService.listByFolder(folderId)

    // Enrich each document with capabilities
    docsWithCaps <- Future.traverse(docs) { doc =>
      MyApp.Document.on(folderId, doc.id).capabilities.map { caps =>
        DocumentListItem(doc.id, doc.name, caps.allowed)
      }
    }
  } yield docsWithCaps
}
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
import myapp.cedar.Actions.Document.DocumentAction
import myapp.cedar.MyApp
import scala.concurrent.ExecutionContext

def listDocumentsWithCapabilities(folderId: String)(
    implicit session: CedarSession[Future], ec: ExecutionContext
): Future[Seq[DocumentListItem]] = {
  for {
    docs <- documentService.listByFolder(folderId)

    // Enrich each document with capabilities
    docsWithCaps <- Future.traverse(docs) { doc =>
      MyApp.Document.on(folderId, doc.id).capabilities.map { caps =>
        DocumentListItem(doc.id, doc.name, caps.allowed)
      }
    }
  } yield docsWithCaps
}
```

</TabItem>
</Tabs>

## Frontend Integration

### REST API Response

When using smithy4s, responses use the generated enum values (uppercase):

```json
{
  "id": "doc-123",
  "name": "Report.pdf",
  "allowedActions": ["READ", "WRITE", "SHARE"]
}
```

### TypeScript Types

When using [Smithy generation](../05-codegen/03-smithy-generation.md), TypeScript
types are generated automatically by smithy4s:

```typescript
// Generated by smithy4s from DocumentAction.smithy
type DocumentAction = "READ" | "WRITE" | "DELETE" | "SHARE";

// Generated from your Smithy API definition
interface Document {
  id: string;
  name: string;
  allowedActions: DocumentAction[];
}
```

### React Usage

```tsx
function DocumentActions({ document }: { document: Document }) {
  const { allowedActions } = document;
  
  return (
    <div>
      {allowedActions.includes("WRITE") && (
        <button onClick={() => editDocument(document.id)}>Edit</button>
      )}
      {allowedActions.includes("DELETE") && (
        <button onClick={() => deleteDocument(document.id)}>Delete</button>
      )}
      {allowedActions.includes("SHARE") && (
        <button onClick={() => shareDocument(document.id)}>Share</button>
      )}
    </div>
  );
}
```

## Caching

Capabilities can be cached at the API layer:

```scala
import myapp.cedar.Actions.Document.DocumentAction

def cachedCapabilities(
    cache: Cache[String, Set[String]],
    resource: MyApp.Document.Ref
)(using session: CedarSession[Future], ec: ExecutionContext): Future[CapabilitySet[DocumentAction]] = {
  val key = s"${resource.resource.toCedarEntity}:${resource.hashCode}"
  cache.get(key) match {
    case Some(cached) =>
      Future.successful(CapabilitySet(cached.flatMap(name => DocumentAction.fromString(name.toUpperCase))))
    case None =>
      resource.capabilities.map { caps =>
        cache.put(key, caps.names, ttl = 1.minute)
        caps
      }
  }
}
```

Invalidate when:

- User permissions change
- Resource permissions change
- User logs out

## Security Notes

Frontend capability checks are for **UI convenience only**. Always enforce authorization on the backend:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
// Frontend shows edit button based on capabilities
// Backend ALWAYS checks permission before allowing edit
def updateDocument(docId: String, updates: Updates) = Action.async { request =>
  given CedarSession[Future] = authFactory.forUser(request.user)

  for {
    // Always check - don't trust frontend
    _ <- MyApp.Document.Edit.on(docId).require
    result <- documentService.update(docId, updates)
  } yield Ok(result)
}
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
// Frontend shows edit button based on capabilities
// Backend ALWAYS checks permission before allowing edit
def updateDocument(docId: String, updates: Updates) = Action.async { request =>
  implicit val session: CedarSession[Future] = authFactory.forUser(request.user)

  for {
    // Always check - don't trust frontend
    _ <- MyApp.Document.Edit.on(docId).require
    result <- documentService.update(docId, updates)
  } yield Ok(result)
}
```

</TabItem>
</Tabs>

