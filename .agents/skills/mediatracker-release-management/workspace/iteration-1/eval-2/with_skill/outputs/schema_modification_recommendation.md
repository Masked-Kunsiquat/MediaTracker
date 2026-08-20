# Schema Modification Recommendation

**Question:** Can I make a column nullable in an entity that was part of the v0.8.0 release by just editing the `@ColumnInfo` in the Entity class?

**Answer:** **No.** You cannot just edit the entity class for a schema that has already been shipped in a tagged release.

## Rationale
According to the **Room Schema Freeze Rule** (defined in `AGENTS.md §8` and the `mediatracker-release-management` skill):
> "Once a release is tagged, the database schema shipped in it is FROZEN. Any later schema change requires incrementing the Room `@Database` version and providing a tested migration."

The `v0.8.0` release corresponds to schema version **5**, which is explicitly listed in the "Frozen schema ledger" in `AGENTS.md`. Modifying it in-place would break the guarantee of schema immutability for existing users.

### Required Steps to Implement the Change
To make the column nullable, you must follow the migration ritual:

1.  **Increment Database Version**:
    Update `APP_DATABASE_VERSION` to `6` in `shared/src/commonMain/kotlin/com/hub/media/core/database/AppDatabase.kt`.

2.  **Update the Entity**:
    Modify the property in your Entity class (e.g., `BookDetailsEntity`) to be a nullable type (e.g., `String?`). Ensure that any `@ColumnInfo(defaultValue = ...)` or validation logic is updated accordingly.

3.  **Create a Migration**:
    Add `MIGRATION_5_6` to `shared/src/commonMain/kotlin/com/hub/media/core/database/Migrations.kt`.
    - Use the `loggedMigration(5, 6)` wrapper.
    - Implement the SQL necessary to change the column nullability (Note: SQLite's `ALTER COLUMN` support is limited; you may need to recreate the table or use a temporary table if simple nullability toggling isn't supported for that specific column type/constraint).

4.  **Add a Migration Test**:
    Add a test case in `shared/src/jvmTest/kotlin/com/hub/media/core/database/MigrationTest.kt` to verify that the schema correctly transitions from version 5 to 6 and preserves data.

5.  **Update the Frozen Schema Ledger**:
    Append a new row to the ledger in `AGENTS.md §8` documenting the new schema version and the migration:

    | Schema | Froze at | Migration into it |
    | :--- | :--- | :--- |
    | v6 | (Upcoming) | `MIGRATION_5_6` — [Brief description of change] |

