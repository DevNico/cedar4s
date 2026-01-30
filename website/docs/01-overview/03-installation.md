---
sidebar_label: Installation
title: Installation
---

# Installation

This page covers sbt setup for cedar4s.

## Supported Scala Versions

- Runtime modules (`cedar4s-core`, `cedar4s-client`, `cedar4s-caffeine`): Scala 2.13 and 3.x
- Codegen/schema modules (`cedar4s-schema`, `cedar4s-codegen`, sbt plugin): Scala 2.12, 2.13, 3.x

## sbt Plugin

Add the cedar4s sbt plugin:

```scala title="project/plugins.sbt"
addSbtPlugin("io.github.devnico" % "sbt-cedar4s" % "{{VERSION}}")
```

Enable the plugin:

```scala title="build.sbt"
enablePlugins(Cedar4sPlugin)
```

## Dependencies

### Core Module

Required for all cedar4s usage:

```scala title="build.sbt"
libraryDependencies += "io.github.devnico" %% "cedar4s-core" % "{{VERSION}}"
```

Provides:
- `EntityFetcher`, `EntityStore`, `EntityCache` traits
- `CedarSession` and authorization request types
- Effect type classes (`Sync`, `Concurrent`, `FlatMap`)
- Built-in `Future` instances

### Client Module

Required for Cedar policy evaluation:

```scala title="build.sbt"
libraryDependencies += "io.github.devnico" %% "cedar4s-client" % "{{VERSION}}"
```

Provides:
- `CedarEngine` - Wraps cedar-java for policy evaluation
- `CedarRuntime` - Creates request-scoped `CedarSession` instances
- `CedarSessionRunner` - Default `CedarSession` implementation

### Caffeine Cache (Optional)

For production caching:

```scala title="build.sbt"
libraryDependencies += "io.github.devnico" %% "cedar4s-caffeine" % "{{VERSION}}"
```

Provides:
- `CaffeineEntityCache` - High-performance entity caching
- `CaffeineCacheConfig` - Configuration presets

## Plugin Settings

### Required Settings

```scala title="build.sbt"
// Path to your Cedar schema file
cedarSchemaFile := baseDirectory.value / "src/main/resources/schema.cedarschema"

// Package for generated Scala code
cedarScalaPackage := "com.example.myapp.cedar"
```

### Optional Settings

```scala title="build.sbt"
// Override root entity detection (empty = auto-detect from schema)
cedarTenantRoots := Set.empty

// Smithy action enum generation (optional)
cedarSmithyNamespace := Some("com.example.api.authz")
cedarSmithyOutputDir := Some(baseDirectory.value / "smithy-specs")
```

**Note:** Typed entity IDs are automatically generated for all entities - no configuration needed!

## Project Structure

Recommended file layout:

```
src/
├── main/
│   ├── resources/
│   │   ├── schema.cedarschema    # Cedar schema
│   │   └── policies/
│   │       └── main.cedar        # Cedar policies
│   └── scala/
│       └── com/example/
│           ├── fetchers/         # EntityFetcher implementations
│           └── Main.scala
└── test/
    └── scala/
        └── com/example/
            └── AuthSpec.scala
```

## Generated Code Location

After running `sbt compile`, generated code appears in:

```
target/scala-*/src_managed/main/<package>/
├── Actions.scala
├── Dsl.scala
├── Entities.scala
├── EntityFetchers.scala
├── Principals.scala
└── ...
```

Import the generated code:

```scala
import com.example.myapp.cedar.MyApp.*
```

## Multi-Module Projects

For multi-module sbt builds, enable the plugin in the module containing your schema:

```scala title="build.sbt"
lazy val core = project
  .enablePlugins(Cedar4sPlugin)
  .settings(
    cedarSchemaFile := baseDirectory.value / "schema.cedarschema",
    cedarScalaPackage := "com.example.cedar"
  )

lazy val app = project
  .dependsOn(core)
```

## Regenerating Code

Generated code updates automatically when:
- The schema file changes
- Plugin settings change
- Running `sbt compile`

To force regeneration:

```bash
sbt clean compile
```

