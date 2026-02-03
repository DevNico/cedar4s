# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.3] - 2026-02-03

### Added

- **Codegen**: Support for entities with multiple parent types (e.g., `entity Membership in [Customer, Permission]`)
  - Generates `Set[ParentId]` fields for each parent type (e.g., `customerIds: Set[CustomerId]`, `permissionIds: Set[PermissionId]`)
  - `toCedarEntity` combines all parent ID sets into the Cedar entity's parent set
  - `getParentIds` extracts parent IDs from all parent type sets
  - `HasParentEvidence` includes evidence for all direct parent relationships

### Changed

- **Codegen**: Parent ID fields are now always `Set[ParentId]` instead of singular `ParentId`, enabling multiple parents of the same type

## [0.1.2] - 2026-02-03

### Changed

- **Codegen**: Generate Scala 2/3 compatible sealed classes for Cedar enum entities (instead of Scala 3 enums) - works with Scala 2.12, 2.13, and 3.x

## [0.1.1] - 2026-02-03

### Fixed

- **Codegen**: Preserve camelCase attribute names (e.g., `firstName` no longer becomes `firstname`)
- **Codegen**: Generate code for Cedar enum entities with `fromString`, `values`, and proper `EntityValue` integration
- **Codegen**: Handle `SetOf` pattern match exhaustivity for nested sets

## [0.1.0] - 2026-01-31

### Added

- Initial public release
- Cedar schema parser with full Cedar 3.x support
- Type-safe Scala code generation from Cedar schemas
- Authorization engine with batch processing
- Entity store with caching support (Caffeine)
- Policy and schema validation
- Observability modules (OpenTelemetry tracing, audit logging)
- SBT plugin for build-time code generation
- Smithy integration (optional)

[unreleased]: https://github.com/DevNico/cedar4s/compare/v0.1.3...HEAD
[0.1.3]: https://github.com/DevNico/cedar4s/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/DevNico/cedar4s/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/DevNico/cedar4s/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/DevNico/cedar4s/releases/tag/v0.1.0
