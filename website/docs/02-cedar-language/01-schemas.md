---
sidebar_label: Schemas
title: Cedar Schemas
---

# Cedar Schemas

Cedar schemas define your authorization model: entities, their relationships, and the actions that can be performed.

## Schema File

cedar4s reads `.cedarschema` files using Cedar's human-readable schema format.

```cedar title="schema.cedarschema"
namespace DocShare

entity User {}

entity Folder {
  owners: Set<User>,
  viewers: Set<User>
}

entity Document in [Folder] {
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
```

## Namespace

Every schema begins with a namespace declaration:

```cedar
namespace DocShare
```

The namespace prefixes all generated entity and action types. In Cedar policies, you
reference them as `DocShare::User`, `DocShare::Action::"Document::Read"`, etc.

## Entities

Entities are the nouns of your authorization model.

### Basic Entity

```cedar
entity User {}
```

Generates a simple entity type with an ID.

### Entity with Attributes

```cedar
entity Document {
  title: String,
  owner: User,
  locked: Bool,
  tags: Set<String>
}
```

Supported attribute types:
- `String`, `Long`, `Bool`
- `Set<T>` - Set of values
- `Record { ... }` - Nested records (including nested entity references)
- Entity references (e.g., `User`) including nested records and sets
- Cedar extension types (e.g., `ipaddr`, `datetime`, `duration`)

### Custom Types

You can define reusable custom types with the `type` keyword:

```cedar
namespace DocShare

type Email = String;
type Metadata = {
  "owner": User,
  "tags": Set<String>
};

entity User {
  email: Email
}

entity Document {
  metadata: Metadata
}
```

Custom types are expanded during code generation and emitted as Scala type aliases or case classes.

### Entity Hierarchy

The `in` keyword defines parent-child relationships:

```cedar
entity Document in [Folder] {}
```

This means a Document can be "in" a Folder. Cedar policies can then use expressions
like `resource in folder` to check containment.

Multiple parents are supported:

```cedar
entity File in [Folder, Project] {}
```

## Actions

Actions define what principals can do to resources.

```cedar
action "Document::Read" appliesTo {
  principal: [User],
  resource: Document
};
```

### Action Components

- **Name** - String identifier (e.g., `"Document::Read"`)
- **principal** - Entity types that can perform this action
- **resource** - Entity type this action applies to
- **context** (optional) - Additional request attributes

### Multiple Principals

```cedar
action "Document::Read" appliesTo {
  principal: [User, ServiceAccount],
  resource: Document
};
```

### Context Attributes

```cedar
action "Document::Read" appliesTo {
  principal: [User],
  resource: Document,
  context: {
    requestTime: Long,
    sourceIp: String
  }
};
```

## Generated Code

From the schema above, cedar4s generates:

| Generated File | Contents |
|----------------|----------|
| `Entities.scala` | Case classes: `Entities.User`, `Entities.Document`, etc. |
| `Actions.scala` | Sealed traits: `Actions.Document.Read`, `Actions.Document.Write` |
| `Dsl.scala` | Resource-centric authorization DSL |
| `Principals.scala` | Principal types: `Principals.User` |
| `Resource.scala` | Resource reference types |

## Feature Matrix

See `docs/SCHEMA_FEATURE_MATRIX.md` for a full comparison of Cedar schema features
and cedar4s support.

