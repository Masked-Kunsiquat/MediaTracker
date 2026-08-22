package com.github.maskedkunisquat.mediatracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hub.media.ui.AddBookViewModel
import com.hub.media.ui.AddMovieViewModel
import com.hub.media.ui.AppContainer
import com.hub.media.ui.BackfillViewModel
import com.hub.media.ui.BackupViewModel
import com.hub.media.ui.BookDetailViewModel
import com.hub.media.ui.ChangelogViewModel
import com.hub.media.ui.EditBookViewModel
import com.hub.media.ui.EditMovieViewModel
import com.hub.media.ui.ExportViewModel
import com.hub.media.ui.ImportViewModel
import com.hub.media.ui.LibraryViewModel
import com.hub.media.ui.LogViewerViewModel
import com.hub.media.ui.MovieDetailViewModel
import com.hub.media.ui.RestoreViewModel
import com.hub.media.ui.SettingsViewModel
import com.hub.media.ui.StatsViewModel

/**
 * Factory for creating [LibraryViewModel] with its [com.hub.media.core.database.MediaRepository]
 * and bulk-delete dependencies from the [AppContainer].
 */
class LibraryViewModelFactory(
    private val appContainer: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(LibraryViewModel::class.java) -> {
                LibraryViewModel(
                    mediaRepository = appContainer.mediaRepository,
                    deleteMediaUseCase = appContainer.deleteMediaUseCase,
                ) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}

/**
 * Factory for creating [AddBookViewModel] with its dependencies from the [AppContainer]:
 * [com.hub.media.features.books.domain.AddBookByIsbnUseCase] (primary add flow),
 * [com.hub.media.features.media.domain.SearchMediaUseCase] (title/author type-ahead),
 * and [com.hub.media.features.books.network.BookSearchProvider] (edition-to-ISBN resolution).
 */
class AddBookViewModelFactory(
    private val appContainer: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(AddBookViewModel::class.java) -> {
                AddBookViewModel(
                    addBookByIsbnUseCase = appContainer.addBookByIsbnUseCase,
                    searchMediaUseCase = appContainer.searchMediaUseCase,
                    searchProvider = appContainer.searchProvider,
                    resolveWorkToEditionsUseCase = appContainer.resolveWorkToEditionsUseCase,
                ) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}

/**
 * Factory for creating [BookDetailViewModel] with its [bookId] and repository/use-case
 * dependencies from the [AppContainer]. Unlike [LibraryViewModelFactory]/[AddBookViewModelFactory],
 * this factory is parameterized per-navigation-argument (one instance per book detail route),
 * so it is constructed fresh in the route wrapper rather than reused.
 */
class BookDetailViewModelFactory(
    private val appContainer: AppContainer,
    private val bookId: String,
) : ViewModelProvider.Factory {
    @OptIn(kotlin.time.ExperimentalTime::class)
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(BookDetailViewModel::class.java) -> {
                BookDetailViewModel(
                    bookId = bookId,
                    mediaRepository = appContainer.mediaRepository,
                    bookRepository = appContainer.bookRepository,
                    readingSessionRepository = appContainer.readingSessionRepository,
                    logReadingSessionUseCase = appContainer.logReadingSessionUseCase,
                    refetchCoverUseCase = appContainer.refetchCoverUseCase,
                    deleteMediaUseCase = appContainer.deleteMediaUseCase,
                ) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}

/**
 * Factory for creating [EditBookViewModel] with its [bookId] and
 * [com.hub.media.features.books.data.BookRepository] dependency from the [AppContainer]
 * (ROADMAP Task 6 Phase A). Per-navigation-argument like [BookDetailViewModelFactory]: constructed
 * fresh in the route wrapper for each `Route.EditBook` navigation rather than reused.
 */
class EditBookViewModelFactory(
    private val appContainer: AppContainer,
    private val bookId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(EditBookViewModel::class.java) -> {
                EditBookViewModel(
                    bookId = bookId,
                    bookRepository = appContainer.bookRepository,
                ) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}

/**
 * Factory for creating [StatsViewModel] with its [com.hub.media.features.stats.data.StatsRepository]/
 * [com.hub.media.features.settings.data.SettingsRepository] dependencies from the [AppContainer]
 * (ROADMAP Task 5 Phase C; the settings dependency was added in Task 7 Phase B so `StatsViewModel`
 * can react to the week-start-day preference — see that class's KDoc). Reused across navigations to
 * the stats screen the same way [LibraryViewModelFactory]/[AddBookViewModelFactory] are (unlike the
 * per-navigation-argument [BookDetailViewModelFactory]).
 */
class StatsViewModelFactory(
    private val appContainer: AppContainer,
) : ViewModelProvider.Factory {
    @OptIn(kotlin.time.ExperimentalTime::class)
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(StatsViewModel::class.java) -> {
                StatsViewModel(appContainer.statsRepository, appContainer.settingsRepository) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}

/**
 * Factory for creating [SettingsViewModel] with its
 * [com.hub.media.features.settings.data.SettingsRepository] dependency from the [AppContainer]
 * (ROADMAP Task 7 Phase B). Reused across navigations to the Settings screen the same way
 * [StatsViewModelFactory]/[LibraryViewModelFactory] are (unlike the per-navigation-argument
 * [BookDetailViewModelFactory]/[EditBookViewModelFactory]).
 */
class SettingsViewModelFactory(
    private val appContainer: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
                SettingsViewModel(appContainer.settingsRepository) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}

/**
 * Factory for creating [ExportViewModel] with its
 * [com.hub.media.features.portability.domain.ExportDataUseCase] dependency from the
 * [AppContainer] (ROADMAP Task 8 Phase A). Reused across navigations to the Settings screen the
 * same way [SettingsViewModelFactory] is -- both ViewModels are constructed side by side by the
 * same route composable.
 */
class ExportViewModelFactory(
    private val appContainer: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(ExportViewModel::class.java) -> {
                ExportViewModel(appContainer.exportDataUseCase) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}

/**
 * Factory for creating [ImportViewModel] with its
 * [com.hub.media.features.portability.domain.ImportDataUseCase] dependency from the
 * [AppContainer] (ROADMAP Task 8 Phase B). Reused across navigations to the Settings screen the
 * same way [ExportViewModelFactory] is -- both ViewModels are constructed side by side by the
 * same route composable.
 */
class ImportViewModelFactory(
    private val appContainer: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(ImportViewModel::class.java) -> {
                ImportViewModel(appContainer.importDataUseCase) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}

/**
 * Factory for creating [BackupViewModel] with its
 * [com.hub.media.features.portability.domain.DatabaseBackupUseCase] dependency from the
 * [AppContainer] (ROADMAP Task 8 Phase C). Reused across navigations to the Settings screen the
 * same way [ExportViewModelFactory] is.
 */
class BackupViewModelFactory(
    private val appContainer: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(BackupViewModel::class.java) -> {
                BackupViewModel(appContainer.backupDatabaseUseCase) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}

/**
 * Factory for creating [RestoreViewModel] with its
 * [com.hub.media.features.portability.domain.RestoreDatabaseUseCase] dependency from the
 * [AppContainer] (ROADMAP Task 8 Phase C). Reused across navigations to the Settings screen the
 * same way [ExportViewModelFactory] is -- see [RestoreViewModel]'s KDoc for why it only ever
 * exposes the non-destructive "stage" half of restore, not the destructive swap itself.
 */
class RestoreViewModelFactory(
    private val appContainer: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(RestoreViewModel::class.java) -> {
                RestoreViewModel(appContainer.restoreDatabaseUseCase, appContainer.settingsRepository) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}

/**
 * Factory for creating [BackfillViewModel] with its
 * [com.hub.media.features.books.domain.BulkBackfillUseCase] dependency from the [AppContainer]
 * (ROADMAP Task 14 Phase A). Reused across navigations to the Settings screen the same way
 * [ExportViewModelFactory] is.
 */
class BackfillViewModelFactory(
    private val appContainer: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(BackfillViewModel::class.java) -> {
                BackfillViewModel(appContainer.bulkBackfillUseCase) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}

/**
 * Factory for creating [LogViewerViewModel] with the [com.hub.media.core.storage.LogFileStore]
 * dependency from the [AppContainer] (ROADMAP Task 15 Phase B2). Reused across navigations to the
 * log viewer the same way [SettingsViewModelFactory] is.
 */
class LogViewerViewModelFactory(
    private val appContainer: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(LogViewerViewModel::class.java) -> {
                LogViewerViewModel(appContainer.logFileStore) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}

/**
 * Factory for [ChangelogViewModel] (ROADMAP Task 15 Phase B2b). Unlike every other factory here it
 * takes no [AppContainer]: the changelog viewer reads an asset and the app's own `versionName`, and
 * touches no repository, database or network at all -- so wiring it through the container would
 * imply a dependency it does not have.
 */
class ChangelogViewModelFactory(
    private val currentVersion: String,
    private val readChangelog: suspend () -> String?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(ChangelogViewModel::class.java) -> {
                ChangelogViewModel(currentVersion, readChangelog) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}

/**
 * Factory for [AddMovieViewModel] (ROADMAP Task 13 Phase B). Manual entry needs only the
 * repository — no provider client, no rate limiter, no cover storage.
 */
class AddMovieViewModelFactory(
    private val appContainer: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(AddMovieViewModel::class.java) -> {
                AddMovieViewModel(movieRepository = appContainer.movieRepository) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}

/**
 * Factory for [MovieDetailViewModel] with its [movieId] (ROADMAP Task 13 Phase B).
 *
 * Takes [AppContainer.deleteMediaUseCase] rather than the repository's own delete for the same
 * reason [BookDetailViewModelFactory] does: deletion must go through the reference-aware path so a
 * poster shared with another item is not removed out from under it.
 */
class MovieDetailViewModelFactory(
    private val appContainer: AppContainer,
    private val movieId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(MovieDetailViewModel::class.java) -> {
                MovieDetailViewModel(
                    movieId = movieId,
                    movieRepository = appContainer.movieRepository,
                    deleteMediaUseCase = appContainer.deleteMediaUseCase,
                ) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}

/** Factory for [EditMovieViewModel] with its [movieId] (ROADMAP Task 13 Phase B). */
class EditMovieViewModelFactory(
    private val appContainer: AppContainer,
    private val movieId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when {
            modelClass.isAssignableFrom(EditMovieViewModel::class.java) -> {
                EditMovieViewModel(
                    movieId = movieId,
                    movieRepository = appContainer.movieRepository,
                ) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
}
