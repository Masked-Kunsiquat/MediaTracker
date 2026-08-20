---
name: mediatracker-release-management
description: Enforce MediaTracker's release ritual and versioning standards. Use this skill when bumping app versions, adding to CHANGELOG.md, modifying Room schemas, or preparing a new release. This skill ensures adherence to the Semantic Versioning scheme, the "Keep a Changelog" discipline, and the strict Room schema freeze rule.
---

# MediaTracker Release Management & Versioning

This skill ensures that the project's history remains clean, versioning is deterministic, and shipped database schemas are never broken.

## Versioning Standard
- **Single Source of Truth**: The app version lives ONLY in `[versions] app` in `gradle/libs.versions.toml`.
- **versionCode Derivation**: Derived in `app/build.gradle.kts` as `major*10000 + minor*100 + patch`. NEVER hand-edit `versionCode`.
- **Scheme**: Semantic Versioning `0.y.z` pre-1.0. Minor bump for features, patch for fixes.

## Changelog Discipline
- **Format**: Follow [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
- **Unreleased**: Every PR must add user-visible changes to the `[Unreleased]` section.
- **Internal Entries**: Use `### Internal` for build-only, CI, or linting changes that the end-user should not see. This section is skipped by the in-app viewer.

## Release Ritual
1. Branch from `main` as `release/vX.Y.Z`.
2. Move `[Unreleased]` content to a dated `## [x.y.z] - YYYY-MM-DD` section.
3. Bump `[versions] app` in `libs.versions.toml`.
4. Commit as "Release vX.Y.Z" and merge once CI is green.
5. Tag the squash commit on `main` as `vX.Y.Z`.

## Room Schema Freeze Rule
- **Immutable Schemas**: Once a version is tagged and shipped, its schema JSON in `shared/schemas/` is FROZEN.
- **Migration Required**: Any subsequent schema change requires a version bump in `AppDatabase.kt` and a tested `Migration` object.
- **Ledger**: Update the "Frozen schema ledger" in `AGENTS.md §8` whenever `APP_DATABASE_VERSION` is bumped. The table is the only record of which versions are frozen.
