# Movie Entity Architectural Suggestion

The project uses a **Polymorphic Media Schema** to ensure universal metadata is shared while domain-specific details remain isolated and type-safe.

### Core Principles
1. **Universal Table (`media_items`)**: Every movie is first represented as a `MediaItemEntity`. This table stores fields common to all media: `id`, `type`, `title`, `releaseYear`, `purchasePrice`, `createdAt`, and `coverImageHash`.
2. **Child Table (`movie_details`)**: Domain-specific data (e.g., director, runtime) is stored in a `MovieDetailsEntity`. This keeps the universal table from becoming bloated with nullable columns that only apply to certain media types.
3. **Primary Key (UUID)**: Following `AGENTS.md §3.1`, all primary keys MUST be generated `UUID` strings. 
4. **Foreign Key Integrity**: The `movie_details` table uses the same `media_id` as its primary key and a foreign key to `media_items.id`. It must use `onDelete = ForeignKey.CASCADE` to ensure that deleting a media item automatically cleans up its specific details.

### Implementation Pattern
- **MediaType**: The `type` column in `MediaItemEntity` is set to `MediaType.MOVIE`.
- **Statelessness**: The entity should be a Kotlin `data class` to support immutability and the `.copy()` pattern for state updates.
- **Watch Status**: A domain-specific `WatchStatus` enum should be used instead of reusing `ReadingStatus`, as the lifecycle of watching a movie (e.g., "Abandoned" vs "DNF") and tracking progress (runtime vs pages) differs.
