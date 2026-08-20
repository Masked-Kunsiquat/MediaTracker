---
name: mediatracker-data-portability
description: Enforce MediaTracker's data portability and CSV standards. Use this skill when modifying DAOs/Entities that are part of the export/import logic, generating sample CSV data, or working on database backup/restore functionality. This skill ensures strict adherence to the RFC 4180 format, header ordering, schema versioning, and the SQLite VACUUM INTO backup protocol.
---

# MediaTracker Data Portability & CSV Standards

This skill ensures that MediaTracker's promise of "first-class data portability" is maintained without silent data corruption or compatibility breaks.

## CSV Formatting Rules (RFC 4180)
- **Escaping**: Quote any field containing a comma, double quote, or newline. Escape double quotes by doubling them (`""`).
- **Encoding**: UTF-8, no BOM.
- **Headers**: Column order is NON-NEGOTIABLE. The importer matches headers against an expected list; reordering will fail the import.
- **Schema Version**: The first column of every data row MUST be `csv_schema_version`. The current version is `2`.
- **Nulls vs. Zeros**: Empty means "unknown". Never use `0`, `N/A`, or `-` for missing data. This is critical for `duration_seconds` (null != 0).

## Data Types
- **Timestamps**: ALWAYS ISO-8601 in UTC (e.g., `2026-01-05T09:15:00Z`).
- **Enums**: Use exact uppercase names (e.g., `FINISHED`, `PAPERBACK`, `PAGES`).
- **Identifiers**: `media_id` is a UUID string. It is the primary join key between library and session files.

## Library Export Structure (`library_export.csv`)
1. `csv_schema_version` (2)
2. `media_id` (UUID)
3. `type` (e.g., `BOOK`, `MOVIE`, `TV_SHOW`)
4. `title`
5. `authors` (joined by `"; "`, empty for non-book types)
6. `release_year` (1450-2100)
... (see `CSV_FORMAT.md` for full 16-column list)

## Import Logic & Duplicate Matching
- **Precedence**: `media_id` -> `isbn` (for books) -> `type` + `title` + `release_year` -> `type` + `title` only (with Review Note).
- **Heterogeneous Support**: `ImportDataUseCase` and `LibraryCsvExporter` MUST support mixed media types uniformly.
- **In-File Duplicates**: Later rows in the same file MUST resolve against earlier rows from the SAME file using these same typed keys.

## Reading Log Structure (`reading_logs_export.csv`)
1. `csv_schema_version` (2)
2. `session_id` (UUID)
3. `media_id` (Join key)
... (see `CSV_FORMAT.md` for full 10-column list)

## Database Backup Protocol
- **Vacuum Into**: Use SQLite's `VACUUM INTO` for backups to ensure a consistent snapshot of a WAL-mode database (not an atomic publication to the final destination).
- **Verification**: Restoring a database MUST validate the first 100 bytes (magic string + `user_version`) and run `PRAGMA integrity_check` before swapping files.
- **Credential Scrubbing**: The backup use case MUST scrub API keys (e.g., `google_books_api_key`) from the snapshot before the file is handed to the user.
