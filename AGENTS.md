# AGENTS.md — Local-First Personal Media Hub

This file serves as the strict architectural and coding guideline for AI agents collaborating on this project. All code suggestions, refactoring, and feature additions MUST adhere to the standards outlined below.

---

## 1. Project Overview & Philosophy

* **Type:** Local-first, privacy-focused media tracking application (Books initially, expanding to Movies & TV Shows).
* **Architecture:** Offline-first, single local SQLite database, zero required external cloud sync or accounts.
* **Tech Stack:** Kotlin Multiplatform (KMP), Jetpack Compose (Android UI), Room KMP (Database), Ktor Client (Networking), Coroutines & Flow (Async/Timers).
* **Core Rule:** User data safety, low resource utilization, and deterministic local storage (content hashing, clean SQL schema) override development shortcuts.

---

## 2. Tech Stack Reference

| Layer | Technology / Library | Guidelines |
| :--- | :--- | :--- |
| **Language** | Kotlin (KMP) | Core business logic MUST reside in the `shared` module. |
| **UI Layer** | Jetpack Compose | Declarative UI, state hoisting, zero legacy XML views. |
| **Database** | Room KMP (SQLite) | Use type-safe DAOs, Flow-based reactive queries, and strict migrations. |
| **Networking** | Ktor Client | Use `kotlinx.serialization` for parsing. Never hardcode API keys. |
| **Concurrency** | Kotlin Coroutines & Flow | Use `StateFlow` and `SharedFlow` for UI state. Avoid raw callbacks. |
| **Asset Storage** | Content-Addressed Local Disk | Compute SHA-256 of raw image bytes for local cover storage. |

---

## 3. Data Model Guidelines

1. **Primary Keys:** ALWAYS use generated `UUID` strings (e.g., `java.util.UUID` or KMP-equivalent string UUIDs) for primary keys. Titles MUST NOT be used as unique identifiers.
2. **Polymorphic Media Schema:**
    * Universal metadata lives in `MediaItems` (id, type, title, release_year, purchase_price, created_at).
    * Domain-specific metadata lives in child tables (`BookDetails`, `MovieDetails`, `TVDetails`) linked by `media_id` FK.
3. **External Identifiers:** Map external API keys (`ISBN`, `TMDB`, `TVDB`) in an `ExternalIdentifiers` table.
4. **Sessions vs. Items:** Keep activity history (`ReadingSessions`, `WatchLogs`) decoupled from `MediaItems` to support re-reading, re-watching, and DNF states cleanly.
5. **Progress Standard:** Store reading session bounds using normalized numeric types to support physical page numbers (`Int`) and e-reader percentages (`Float`).

---

## 4. API & Image Handling Standards

* **Books API:** Primary: Open Library API. Secondary Fallback: Google Books API.
* **Movies & TV API:** Primary: TMDB API (The Movie Database).
* **Cover/Poster Storage Protocol:**
    1. Download image bytes via Ktor.
    2. Compute SHA-256 hash of the raw `ByteArray`.
    3. Save to app storage as `<hash>.jpg`.
    4. Store relative path or hash string in database (`cover_local_hash`).
    5. If the file `<hash>.jpg` already exists, skip file writing (automatic deduplication).

---

## 5. Coding Standards & Conventions

* **Immutability:** Prefer `val` over `var`. Use Kotlin data classes with `.copy()` for state updates.
* **State Hoisting:** Compose UI components must be stateless where possible, accepting state objects and emitting events up to the ViewModel.
* **Error Handling:** Network calls and database operations MUST be wrapped in custom `Result<T>` or sealed `Resource` classes to prevent UI crashes on offline/error states.
* **No Unnecessary Dependencies:** Do not add third-party libraries without explicit project context approval. Stick to the primary KMP toolchain.

---

## 6. Directory Structure Blueprint

```text
shared/
 ├── src/commonMain/kotlin/com/hub/media/
 │    ├── core/
 │    │    ├── database/      <-- Room Database, DAOs, Entities
 │    │    ├── network/       <-- Ktor Client & API definitions
 │    │    ├── storage/       <-- SHA-256 File Manager & Disk I/O
 │    │    └── util/          <-- Dispatchers, Extensions, Result wrappers
 │    ├── features/
 │    │    ├── books/         <-- Timer, Reading Logs, ISBN Fetcher
 │    │    ├── movies/        <-- Movie Logs, TMDB Client
 │    │    ├── tv/            <-- Season/Episode Progression
 │    │    └── stats/         <-- Analytics Queries & Aggregate Flow
 │    └── ui/                <-- Shared ViewModels & UI Contracts
androidApp/                   <-- Android Jetpack Compose Screens & Entry Point
```

---
## 7. Testing Standards & Quality Assurance

* **Frameworks:** Use `kotlin.test` for shared KMP unit tests and `kotlinx-coroutines-test` for testing Flows and Coroutines.
* **Coverage Requirements:**
  - Every Repository, UseCase, and Utility function MUST have accompanying unit tests.
  - Test edge cases: 0-page books, 0-second timers, missing API metadata fields, corrupt image byte arrays.
* **Test Location:** Place unit tests in `shared/src/commonTest/kotlin/`.
* **Verification Command:** AI agents MUST run `./gradlew test` to ensure all tests pass before completing any task.
---

## 8. Versioning & Release Standards

* **Scheme:** Semantic Versioning `0.y.z` pre-1.0. Minor bump per feature milestone, patch for fixes. `1.0.0` when the app is daily-drivable.
* **Single Source of Truth:** The app version lives ONLY in `[versions] app` in `gradle/libs.versions.toml`. `app/build.gradle.kts` reads `versionName` from it and derives `versionCode` as `major*10000 + minor*100 + patch`. NEVER hand-edit `versionCode` or duplicate the version string elsewhere.
* **Changelog Discipline:** `CHANGELOG.md` follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Every completed task/phase MUST add its user-visible changes to the `[Unreleased]` section in the same commit (or the phase commit immediately following). Agents finishing a phase without touching the changelog have not finished the phase.
* **Release Ritual:** (1) move `[Unreleased]` content into a dated `## [x.y.z] - YYYY-MM-DD` section, (2) bump `[versions] app`, (3) commit as `Release vX.Y.Z`, (4) `git tag vX.Y.Z`.
* **Room Schema Freeze Rule:** Once a release is tagged, the database schema shipped in it is FROZEN. Any later schema change requires incrementing the Room `@Database` version and providing a tested migration (`Migration` object + migration test). In-place edits of the current schema version are permitted ONLY for schema versions that have never been part of a tagged release. Schema v1 froze at `v0.1.0`.
