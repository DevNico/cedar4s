---
sidebar_label: Policies
title: Cedar Policies
---

# Cedar Policies

Cedar policies define authorization rules: who can do what, under which conditions.

## Policy Files

Policies are written in `.cedar` files, separate from the schema:

```cedar title="policies/main.cedar"
// Folder owners can read their folders
permit (
  principal,
  action == DocShare::Action::"Folder::Read",
  resource
) when {
  principal in resource.owners
};

// Document owners can read and write
permit (
  principal,
  action in [
    DocShare::Action::"Document::Read",
    DocShare::Action::"Document::Write"
  ],
  resource
) when {
  principal == resource.owner
};
```

## Policy Structure

Every policy has three parts:

```cedar
permit|forbid (
  principal [== <entity> | in <group>],
  action [== <action> | in [<actions>]],
  resource [== <entity> | in <parent>]
) [when { <conditions> }];
```

### Effect

- `permit` - Allow the request if conditions match
- `forbid` - Deny the request if conditions match (takes precedence)

### Scope

Constrain which principals, actions, and resources the policy applies to:

```cedar
// Any principal, specific action, any resource
permit (principal, action == DocShare::Action::"Document::Read", resource);

// Specific principal type
permit (principal is DocShare::User, action, resource);

// Resource in a parent
permit (principal, action, resource in DocShare::Folder::"shared-folder");
```

### Conditions

The `when` clause adds attribute-based conditions:

```cedar
permit (principal, action, resource)
when {
  principal in resource.viewers &&
  resource.locked == false
};
```

## Loading Policies

cedar4s loads policies at runtime:

```scala
val engine = CedarEngine.fromResources[Future](
  policiesPath = "policies",
  policyFiles = Seq("main.cedar", "admin.cedar")
)
```

Policies are loaded from the classpath (typically `src/main/resources/policies/`).

## Policy Evaluation

Cedar evaluates policies using **default-deny**:

1. If any `forbid` policy matches → **Deny**
2. If any `permit` policy matches → **Allow**
3. Otherwise → **Deny**

This means you must have at least one `permit` policy for any action to succeed.

## Common Patterns

### Role-Based Access

```cedar
// Admins can do anything
permit (principal, action, resource)
when { principal in DocShare::Role::"admin" };
```

### Hierarchical Access

```cedar
// Access to folder grants access to documents in it
permit (principal, action == DocShare::Action::"Document::Read", resource)
when { resource in principal.folders };
```

### Time-Based Access

```cedar
permit (principal, action, resource)
when {
  context.requestTime >= resource.accessStart &&
  context.requestTime <= resource.accessEnd
};
```

