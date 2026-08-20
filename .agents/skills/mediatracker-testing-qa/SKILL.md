---
name: mediatracker-testing-qa
description: Enforce MediaTracker's testing and quality assurance standards. Use this skill whenever writing new tests, modifying existing test suites, or investigating CI failures. This skill ensures tests are placed in the correct module (commonTest vs jvmTest), use the authoritative verification commands, and correctly handle asynchronous Flow state to avoid flakes.
---

# MediaTracker Testing & QA Standards

This skill ensures all tests in the MediaTracker project are reliable, authoritative, and follow the guidelines in `AGENTS.md §7`.

## Verification Commands
- **Authoritative Data Layer Run**: ALWAYS use `./gradlew :shared:jvmTest :shared:testDebugUnitTest`. 
- **CRITICAL**: Do NOT use `./gradlew test`. It misses `:shared:jvmTest`, which is the ONLY run that exercises the Room data layer (DAOs, migrations, repositories).
- **App Build**: Run `./gradlew :app:assembleDebug` when touching manifests, resources, or Gradle config.
- **Instrumented Tests**: Run `./gradlew :app:connectedDebugAndroidTest` for UI verification (requires a device).

## Test Location Rules
- **Default**: `shared/src/commonTest/kotlin/` (runs on all targets).
- **JVM-Only**: `shared/src/jvmTest/kotlin/` for tests needing real file I/O or Room's `MigrationTestHelper`.
- **UI/Compose**: `app/src/androidTest/` for instrumented UI tests.

## Asynchronous Flow Testing (Crucial)
To avoid flaky tests in ViewModels that use `stateIn` or `combine`:
1. **Never read `.value` immediately after an action.** The state change requires a dispatch and may not have reached the Flow yet.
2. **Await the state**: Use `uiState.first { <condition> }` or `runCurrent()` on a `TestDispatcher`.
3. **Initial State**: Reading `.value` is ONLY safe for the initial state before any actions occur.

## Concurrency Testing
- If a test needs to verify a race condition or a guard (e.g., a double-tap guard), use a `StandardTestDispatcher`.
- This allows you to *enqueue* work without it executing immediately, letting you verify that a second call is correctly ignored while the first is pending.

## General Quality Rules
- **No assumption of existing data**: Tests must be self-seeding. Instrumented tests uninstall the app after running.
- **Positive & Negative Controls**: Assert that the forbidden thing is absent AND that the expected thing is present.
- **Polymorphic Test Data**: When testing generalized components (like `LibraryViewModel`), ensure the test data includes a mix of media types (e.g., `MediaWithDetails.Book` and a non-book variant like a planned `MediaWithDetails.Movie`) to exercise polymorphic behavior.
- **Unique Anchors**: When script-editing tests, anchor on unique literals to avoid patching the wrong test body.
