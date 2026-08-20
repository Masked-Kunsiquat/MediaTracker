---
name: mediatracker-architecture-standards
description: Enforce MediaTracker's core architectural and coding standards. Use this skill whenever adding or modifying data models (DAOs, Entities), networking logic (Ktor clients), or storage protocols (Image handling). This skill ensures adherence to the local-first, privacy-focused philosophy, KMP tech stack, and specific implementation rules like UUID primary keys and SHA-256 content-addressed image storage.
---

# MediaTracker Architecture Standards

This skill ensures all contributions to the MediaTracker project follow the strict architectural guidelines defined in `AGENTS.md`.

## Core Philosophy
- **Local-first, privacy-focused**: No required cloud sync or accounts.
- **Offline-first**: Single local SQLite database (Room KMP).
- **User data safety**: Deterministic local storage and clean SQL schema are top priorities.

## Tech Stack Requirements
- **Kotlin Multiplatform (KMP)**: Business logic MUST be in the `shared` module.
- **UI Layer**: Jetpack Compose (declarative, stateless, state hoisting).
- **Database**: Room KMP (DAOs, Flow-based reactive queries, strict migrations).
- **Networking**: Ktor Client with `kotlinx.serialization`. **Never hardcode API keys.**

## Data Model Rules
1. **Primary Keys**: ALWAYS use generated UUID strings (e.g., `java.util.UUID` or KMP-equivalent). Do NOT use titles or sequential integers as IDs.
2. **Polymorphic Media Schema**: 
   - `MediaItems` table for universal metadata (id, type, title, etc.).
   - Child tables (e.g., `BookDetailsEntity`, `MovieDetailsEntity`) linked by `mediaId` Foreign Key.
3. **Polymorphic Representation**: Use the `MediaWithDetails` sealed class (e.g., `MediaWithDetails.Book`) to wrap an item and its domain-specific metadata.
4. **Immutability**: Prefer `val` over `var`. Use data classes with `.copy()` for state updates.

## Repository & Use Case Standards
- **MediaRepository**: Handles universal operations on the base `MediaItemEntity` (e.g., deletion).
- **Domain Repositories**: (e.g., `BookRepository`) handle domain-specific CRUD but MUST return polymorphic `MediaWithDetails` types.
- **Result Wrapping**: All database writes and network calls MUST be wrapped in `Resource` or `Result` types.

## Networking & Image Standards
- **User-Agent**: Every outbound request MUST be identified using the `USER_AGENT` constant from `core/network/HttpClientFactory.kt`. This is critical for rate-limiting compliance with providers like Open Library.
- **Image Storage Protocol**:
  1. Compute SHA-256 of raw bytes.
  2. Save to app storage as `<hash>.jpg`.
  3. Store hash string in database (`cover_local_hash`).
  4. Skip writing if file already exists (automatic deduplication).

## Implementation Rules
- **No Unnecessary Dependencies**: Stick to the primary KMP toolchain.
- **No Credentials**: Never commit secrets or binary keystores. CI uses gitleaks for enforcement.
- **Formatting**: Use ktlint at a 120-column limit. Run `./gradlew ktlintFormat` before committing.
