# cedar4s

[![Scala CI](https://github.com/devnico/cedar4s/actions/workflows/ci.yml/badge.svg)](https://github.com/devnico/cedar4s/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Type-safe [Cedar](https://www.cedarpolicy.com/) authorization for Scala. Generates type-safe Scala code from Cedar schemas with compile-time validation and effect polymorphism.

> **Early Stage** - cedar4s is under active development. APIs may change between releases. We'd love your feedback — please [open an issue](https://github.com/DevNico/cedar4s/issues) with questions, suggestions, or anything you run into.

## Installation

```scala
// project/plugins.sbt
addSbtPlugin("io.github.devnico" % "sbt-cedar4s" % "0.1.0-SNAPSHOT")

// build.sbt
enablePlugins(Cedar4sPlugin)
libraryDependencies += "io.github.devnico" %% "cedar4s-client" % "0.1.0-SNAPSHOT"
```

## Quick Example

```scala
import example.docshare.cedar.*

// Create runtime (once at startup)
val runtime = CedarRuntime(engine, entityStore, principalResolver)

// Create session (per request)
given session: CedarSession[Future] = runtime.session(Principal.User(userId))

// Type-safe authorization checks
Document.View.on(DocumentId("doc-1")).require

// Compose checks with AND/OR
(Folder.View.on(folderId) & Document.View.on(docId)).require

// Batch operations
val allowed = session.filterAllowed(documentIds)(id => Document.View.on(id))
```

[Full Documentation →](https://devnico.github.io/cedar4s/)
