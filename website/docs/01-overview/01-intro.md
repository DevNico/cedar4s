---
sidebar_label: Introduction
title: Introduction
---

# Introduction

:::info Early Stage
cedar4s is under active development. APIs may change between releases. We'd love your feedback — please [open an issue](https://github.com/DevNico/cedar4s/issues) with questions, suggestions, or anything you run into.
:::

**cedar4s** generates type-safe Scala code from [Cedar](https://www.cedarpolicy.com/) authorization schemas.

Instead of scattering string-based permission checks throughout your codebase, you
define your authorization model once in Cedar's schema language. cedar4s then generates
Scala code that makes invalid authorization requests impossible to express.

cedar4s is heavily inspired by [smithy4s](https://disneystreaming.github.io/smithy4s/), bringing the same code-generation philosophy to authorization. Where smithy4s generates API clients and servers from Smithy specifications, cedar4s generates authorization code from Cedar schemas. Even the logo follows this theme: smithy4s is the missing hammer for the anvil, cedar4s is the missing axe for the cedar forest.

## Features

- **Type-safe actions** - Actions are sealed traits, not strings. Typos become compile errors.
- **Composable checks** - Combine authorization requests with `&` (AND) and `|` (OR).
- **Deferred resolution** - Automatically fetch parent entity IDs via `EntityStore`.
- **Effect-polymorphic** - Works with `Future`, cats-effect `IO`, `ZIO`, or any `F[_]`.
- **Built-in caching** - Caffeine-based entity caching for production performance.
- **Batch operations** - Efficiently authorize multiple resources in a single pass.

## How It Works

1. You write a Cedar schema defining entities, actions, and relationships
2. cedar4s generates Scala code: action types, entity classes, and a DSL
3. You implement `EntityFetcher` to load your domain models
4. Your app uses the generated DSL for type-safe authorization checks

```scala
import myapp.cedar.MyApp

// Generated from your Cedar schema - compile-time checked
MyApp.Document.Read(folderId, documentId).require

// Composes naturally
val canAccess = MyApp.Folder.Read(folderId) & MyApp.Document.Read(folderId, documentId)
canAccess.require
```

## When to Use cedar4s

cedar4s is designed for applications with **fine-grained, hierarchical authorization**:

- Document management systems (folders containing documents)
- Multi-tenant SaaS (organizations, workspaces, projects)
- Collaboration tools (teams, channels, resources)
- Any domain where "who can access what" has structure

If your authorization is simple boolean flags or role checks, a dedicated authorization library may be overkill.

