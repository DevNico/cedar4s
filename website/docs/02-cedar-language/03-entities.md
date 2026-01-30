---
sidebar_label: Entities
title: Entity Relationships
---

# Entity Relationships

Cedar's entity model supports hierarchical relationships that map naturally to real-world authorization patterns.

## Entity Hierarchy

The `in` keyword creates parent-child relationships:

```cedar
entity Document in [Folder] {}
```

This allows policies to express containment:

```cedar
permit (principal, action, resource)
when { resource in principal.folders };
```

## Multiple Parents

Entities can have multiple parent types:

```cedar
entity File in [Folder, Project, SharedDrive] {}
```

A File instance can be in any combination of these parents.

## Transitive Containment

Cedar's `in` operator is transitive. If:
- Document D is in Folder F
- Folder F is in Workspace W

Then `D in W` is true.

This enables natural hierarchical policies:

```cedar
// Workspace access implies access to all folders and documents
permit (principal, action == DocShare::Action::"Document::Read", resource)
when { resource in principal.workspaces };
```

## Entity Attributes

Entities can have attributes for fine-grained conditions:

```cedar
entity Document in [Folder] {
  owner: User,
  editors: Set<User>,
  locked: Bool,
  classification: String
}
```

Use attributes in policy conditions:

```cedar
permit (principal, action == DocShare::Action::"Document::Write", resource)
when {
  principal in resource.editors &&
  resource.locked == false
};
```

## Generated Entity Classes

cedar4s generates case classes for each entity:

```scala
// Generated from schema
object Entities {
  case class Document(
    id: String,
    folderId: String,  // From `in [Folder]`
    owner: String,
    editors: Set[String],
    locked: Boolean,
    classification: String
  )
}
```

Your `EntityFetcher` implementation creates these from your domain models.

## Parent Resolution

When you use deferred checks (`.on(id)`), cedar4s resolves parent IDs automatically:

```scala
// cedar4s calls your DocumentFetcher, extracts folderId
import myapp.cedar.MyApp.*
Document.Read.on(documentId).require
```

The `EntityStore` uses the generated entity's parent fields to build the complete resource reference.

