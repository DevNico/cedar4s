---
sidebar_label: Customization
title: Code Generation Customization
---

# Code Generation Customization

Configure cedar4s code generation to match your project's needs.

## Plugin Settings

### Schema File

```scala title="build.sbt"
// Cedar schema file (required)
cedarSchemaFile := baseDirectory.value / "src" / "main" / "resources" / "schema" / "myapp.cedarschema"
```

### Output Package

```scala title="build.sbt"
// Package for generated Scala code
cedarScalaPackage := "com.example.myapp.cedar"
```

Generated code is written to sbt's managed sources directory
(`target/scala-X.Y/src_managed/main/`), organized by package structure.

## Typed IDs

cedar4s automatically generates typed ID newtypes for every entity in your schema.
These provide compile-time type safety with zero runtime overhead. See
[Typed IDs](./02-refinements.md) for details.

## Tenant Roots

Override automatic root entity detection:

```scala title="build.sbt"
// Empty = auto-detect from schema (default)
cedarTenantRoots := Set.empty

// Explicit roots
cedarTenantRoots := Set("Organization", "Tenant")
```

Root entities are those without parents in the entity hierarchy. These affect how
`loadEntityWithParents` traverses the graph.

## Smithy Generation

Generate Smithy action enums for use with smithy4s APIs:

```scala title="build.sbt"
// Enable Smithy generation
cedarSmithyNamespace := Some("com.example.api.authz")
cedarSmithyOutputDir := Some(baseDirectory.value / "smithy-specs")
```

Both settings are required to enable Smithy generation. See [Smithy Generation](./03-smithy-generation.md) for details.

## All Settings Reference

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `cedarSchemaFile` | `File` | `src/main/resources/schema/schema.cedarschema` | Path to Cedar schema |
| `cedarScalaPackage` | `String` | `"cedar.policies"` | Package for generated Scala |
| `cedarTenantRoots` | `Set[String]` | `Set.empty` | Override root detection |
| `cedarSmithyNamespace` | `Option[String]` | `None` | Smithy namespace |
| `cedarSmithyOutputDir` | `Option[File]` | `None` | Smithy output directory |

## sbt Task

| Task | Description |
|------|-------------|
| `cedarCodegen` | Generate Scala code from Cedar schema |

The task runs automatically during `compile`. To run manually:

```bash
sbt cedarCodegen
```

## Multi-Module Projects

For projects with separate API and implementation modules:

```scala title="build.sbt"
lazy val cedarApi = project
  .enablePlugins(Cedar4sPlugin)
  .settings(
    cedarSchemaFile := file("cedar/schema.cedarschema"),
    cedarScalaPackage := "com.example.cedar"
  )

lazy val app = project
  .dependsOn(cedarApi)
  .settings(
    libraryDependencies += "io.github.devnico" %% "cedar4s-core" % "@VERSION@"
  )
```

## Environment-Specific Configuration

Use sbt settings for different schemas per project:

```scala title="build.sbt"
val cedarSettings = Seq(
  cedarSchemaFile := file("src/main/cedar/schema.cedarschema"),
  cedarScalaPackage := "com.example.cedar"
)

val testCedarSettings = Seq(
  cedarSchemaFile := file("src/test/cedar/test-schema.cedarschema"),
  cedarScalaPackage := "com.example.cedar.test"
)

lazy val app = project
  .enablePlugins(Cedar4sPlugin)
  .settings(cedarSettings)

lazy val testSupport = project
  .enablePlugins(Cedar4sPlugin)
  .settings(testCedarSettings)
```

