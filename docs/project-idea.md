Here is a comprehensive summary of the app concept, goals, and architectural decisions established during our brainstorming session.

---

# Local-First Personal Media Hub

## Executive Summary & Vision

The goal is to build a **local-first, privacy-focused tracking application** (initially focused on books and reading habits, with a planned modular expansion into movies and TV shows). Built using **Kotlin Multiplatform (KMP)** with an **Android-first (Jetpack Compose)** design, the application prioritizes local storage, zero cloud dependencies, data portability via CSV, and rich offline analytics.

---

## 1. Core Objectives & Feature Set

### Phase 1: Local-First Book Tracker

* **Local-First Architecture:** SQLite database running locally on-device with zero required account creation or cloud lock-in.
* **Data Portability:** First-class support for exporting and importing data via clean CSV files (`library_export.csv`, `reading_logs_export.csv`) and `.sqlite` database backups.
* **Automated & Custom Metadata Fetching:**
* Primary lookup via **Open Library API**; secondary fallback via **Google Books API**.
* Multi-input support: Manual entry, ISBN typing, and future barcode scanning.
* Captures title, authors, page counts, edition format (hardcover, paperback, e-book, audiobook), publication year, purchase date, price, and store source.


* **Reading Progress Engine:**
* **Live Timer:** Active session tracking with Start, Pause, Resume, and Stop controls that automatically calculates reading velocity.
* **Dual Unit Support:** Standardized tracking for physical page numbers (`12` $\to$ `35`) and e-reader percentages (`12.5%` $\to$ `18.0%`).
* **Historical Log & Backfilling:** Retroactive session logging with custom dates, timestamps, and notes.
* **Re-Reading Mechanics:** Tracks completion milestones and individual session histories per read-through.


* **Categorization & Genres:**
* Multi-genre tagging per title with custom user taxonomies and hex color coding.
* Rich filtering across formats, reading status, purchase dates, and genres.


* **Analytics & Financial Insights:**
* Reading speed metrics (pages/hour, min/page), streaks, and GitHub-style activity heatmaps.
* Shelf financial totals, average cost per book, and cost per page read.



### Phase 2: Multi-Media Hub Expansion

* **Unified Dashboard:** A central landing screen providing cross-media aggregate statistics (e.g., total entertainment time, combined monthly spending, active reading timers, "Up Next" queue).
* **Dedicated Sub-Apps:** Independent domain modules for **Movies** (runtimes, cinema vs. home, TMDB integration) and **TV Shows** (season/episode progression, episode logs).

---

## 2. Technical Stack & Architecture Blueprint

```text
+-----------------------------------------------------------------------------------+
|                            Kotlin Multiplatform (KMP)                             |
+-----------------------------------------------------------------------------------+
|  UI Layer:               | Jetpack Compose (Android) / Compose Multiplatform       |
|  Database Layer:         | Room KMP / SQLDelight (Local SQLite Engine)            |
|  Networking:             | Ktor HTTP Client (Open Library / Google Books APIs)   |
|  Asynchronous Logic:     | Kotlin Coroutines & Flow (Live Timers & Async Storage)|
|  Serialization:          | kotlinx.serialization                                  |
+-----------------------------------------------------------------------------------+

```

---

## 3. Data Model & Architecture Decisions

### A. Modular Schema (Core vs. Specific Attributes)

To accommodate books, movies, and TV shows without cluttering tables, the database separates base metadata from domain-specific details using unique generated **UUIDs** as primary keys:

```
+---------------------------------------------------------------------------------+
|                                MediaItems (Base)                                |
+---------------------------------------------------------------------------------+
| id (UUID) | type ("BOOK", "MOVIE", "TV") | title | release_year | purchase_price  |
+---------------------------------------------------------------------------------+
           |                                       |
           v                                       v
+--------------------+                   +--------------------+
|    BookDetails     |                   |     TVDetails      |
+--------------------+                   +--------------------+
| media_id (FK)      |                   | media_id (FK)      |
| isbn               |                   | total_seasons      |
| format             |                   | total_episodes     |
| total_pages        |                   +--------------------+
+--------------------+

```

### B. Deduplication & Naming Collisions

* **External Identifiers Table:** Stores API mapping (`ISBN`, `TMDB`, `TVDB`) alongside internal UUIDs to prevent title collisions (e.g., *Dune* the 1965 Novel vs. *Dune* the 2021 Movie) and prompt for re-reads/re-watches when duplicate items are scanned.
* **Decoupled Activity Logs:** Separates media metadata from `ReadingSessions` / `WatchLogs` to seamlessly support re-reading and DNF (Did Not Finish) tracking.

### C. Content-Based Asset Hashing

* Cover art and poster images are stored locally using **SHA-256 Content Hashing** of raw image bytes (e.g., `a591a6d40bf42...jpg`).
* **Benefits:**
* **Storage Deduplication:** Identical image payloads fetched from different URLs/APIs are saved once on disk.
* **Filesystem Safety:** Prevents invalid file name crashes caused by special characters (`:`, `/`, `?`) in titles.
* **Integrity & Garbage Collection:** Simplifies corruption checks and clean removal of orphaned cover files when deleting items.



---