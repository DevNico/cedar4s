---
sidebar_label: Composition
title: Composing Authorization Checks
---

# Composing Authorization Checks

Combine multiple authorization checks using `&` (AND) and `|` (OR) operators.

:::note Deferred Checks Must Be Resolved First
`DeferredAuthCheck` (created by `.on(id)`) cannot be directly composed with `&` and `|` operators. You must either:
1. Call `.resolve` to convert to `AuthCheck` before composing, OR
2. Execute the deferred check directly with `.run`, `.require`, or `.isAllowed`

```scala
// This works - deferred checks execute directly
Document.View.on(docId).require

// This does NOT work - cannot compose deferred checks
val bad = Document.View.on(docId) & Folder.View.on(folderId)  // Compile error

// This works - resolve first, then compose
val good = for {
  docCheck <- Document.View.on(docId).resolve
  folderCheck <- Folder.View.on(folderId).resolve
} yield (docCheck & folderCheck)
```

For most use cases, execute deferred checks directly or use batch operations instead of composition.
:::

## AND Composition

All checks must pass. To compose deferred checks, resolve them first:

```scala
import myapp.cedar.MyApp.*

given session: CedarSession[Future] = runtime.session(currentUser)

// Resolve deferred checks, then compose
val both: Future[AuthCheck.All] = for {
  folderCheck <- Folder.Read.on(FolderId("folder-1")).resolve
  docCheck <- Document.Read.on(DocumentId("doc-1")).resolve
} yield folderCheck & docCheck

// Execute the composed check
both.flatMap(_.require)
```

If any check fails, the entire composition fails.

## OR Composition

At least one must pass:

```scala
import myapp.cedar.MyApp.*

given session: CedarSession[Future] = runtime.session(currentUser)

// Resolve deferred checks, then compose
val either: Future[AuthCheck.AnyOf] = for {
  updateCheck <- Document.Update.on(DocumentId("doc-1")).resolve
  deleteCheck <- Document.Delete.on(DocumentId("doc-1")).resolve
} yield updateCheck | deleteCheck

// Execute the composed check
either.flatMap(_.require)
```

The first successful check short-circuits evaluation.

## Complex Composition

Combine AND and OR:

```scala
import myapp.cedar.MyApp.*

given session: CedarSession[Future] = runtime.session(currentUser)

val complex: Future[AuthCheck] = for {
  folderCheck <- Folder.Read.on(FolderId("folder-1")).resolve
  docCheck <- Document.Read.on(DocumentId("doc-1")).resolve
  adminCheck <- Admin.Override.on(UserId("admin-1")).resolve
} yield (folderCheck & docCheck) | adminCheck

complex.flatMap(_.isAllowed)
```

This checks: "User can read the folder AND the document, OR user has admin override."

## Practical Examples

### Editor or Owner

```scala
given session: CedarSession[Future] = runtime.session(currentUser)

val canEdit: Future[AuthCheck] = for {
  editCheck <- Document.Edit.on(DocumentId("doc-1")).resolve
  ownCheck <- Document.Own.on(DocumentId("doc-1")).resolve
} yield editCheck | ownCheck

canEdit.flatMap(_.require)
```

### Hierarchical Access

```scala
given session: CedarSession[Future] = runtime.session(currentUser)

val access: Future[AuthCheck] = for {
  folderCheck <- Folder.Read.on(FolderId("folder-1")).resolve
  readCheck <- Document.Read.on(DocumentId("doc-1")).resolve
  editCheck <- Document.Edit.on(DocumentId("doc-1")).resolve
  adminCheck <- Document.Admin.on(DocumentId("doc-1")).resolve
} yield folderCheck & (readCheck | editCheck | adminCheck)

access.flatMap(_.require)
```

### Multiple Resources

```scala
given session: CedarSession[Future] = runtime.session(currentUser)

val canMove: Future[AuthCheck] = for {
  docCheck <- Document.Read.on(DocumentId("doc-1")).resolve
  folderCheck <- Folder.Write.on(FolderId("dest-folder")).resolve
} yield docCheck & folderCheck

canMove.flatMap(_.require)
```

## Type Safety

Composition preserves type information:

```scala
given session: CedarSession[Future] = runtime.session(currentUser)

// Single deferred check
val single: DeferredAuthCheck[Future, DocumentId, ...] = Document.Read.on(DocumentId("doc-1"))

// Composed checks (after resolve)
val all: Future[AuthCheck.All] = for {
  check1 <- Document.Read.on(DocumentId("doc-1")).resolve
  check2 <- Folder.Read.on(FolderId("folder-1")).resolve
} yield check1 & check2

val anyOf: Future[AuthCheck.AnyOf] = for {
  check1 <- Document.Read.on(DocumentId("doc-1")).resolve
  check2 <- Document.Edit.on(DocumentId("doc-1")).resolve
} yield check1 | check2
```

All composition types (`Single`, `All`, `AnyOf`) support `.run`, `.require`, `.isAllowed`.

## Evaluation Order

- **AND (`&`)**: Checks run sequentially, fails fast on first denial
- **OR (`|`)**: Checks run sequentially, succeeds fast on first approval

For performance-sensitive code, place the most likely check first in OR compositions.

