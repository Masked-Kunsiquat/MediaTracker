package com.github.maskedkunisquat.mediatracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hub.media.ui.AddBookViewModel
import com.hub.media.ui.AddMovieViewModel
import com.hub.media.ui.AddTVShowViewModel
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
import com.hub.media.ui.MovieSearchViewModel
import com.hub.media.ui.RestoreViewModel
import com.hub.media.ui.SettingsViewModel
import com.hub.media.ui.StatsViewModel
import com.hub.media.ui.TVShowDetailViewModel
import com.hub.media.ui.TVShowSearchViewModel

/**
 * Base [ViewModelProvider.Factory] shared by every factory in this file.
 *
 * Before this class existed, all 18 factories below each carried their own copy of the same
 * `isAssignableFrom` check-then-cast boilerplate; the only parts that ever varied between them
 * were which [ViewModel] class to check against and how to construct it. This class hoists that
 * identical ~10-line body into one place, taking the class and a `construct` lambda as the only
 * two variable parts.
 *
 * The 18 named subclasses are kept -- rather than collapsing every call site to a bare
 * `AppViewModelFactory(X::class.java) { X(...) }` or some DSL -- specifically so every existing
 * call site keeps compiling unchanged and every `[XyzViewModelFactory]` KDoc link elsewhere in the
 * codebase keeps resolving to a real, still-documented class.
 */
open class AppViewModelFactory<VM : ViewModel>(
    private val viewModelClass: Class<VM>,
    private val construct: () -> VM,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(viewModelClass)) {
            error("Unknown viewmodel class: $modelClass")
        }
        return construct() as T
    }
}

/**
 * Factory for creating [LibraryViewModel] with its [com.hub.media.core.database.MediaRepository]
 * and bulk-delete dependencies from the [AppContainer].
 */
class LibraryViewModelFactory(
    appContainer: AppContainer,
) : AppViewModelFactory<LibraryViewModel>(
        LibraryViewModel::class.java,
        {
            LibraryViewModel(
                mediaRepository = appContainer.mediaRepository,
                deleteMediaUseCase = appContainer.deleteMediaUseCase,
            )
        },
    )

/**
 * Factory for creating [AddBookViewModel] with its dependencies from the [AppContainer]:
 * [com.hub.media.features.books.domain.AddBookByIsbnUseCase] (primary add flow),
 * [com.hub.media.features.media.domain.SearchMediaUseCase] (title/author type-ahead),
 * and [com.hub.media.features.books.network.BookSearchProvider] (edition-to-ISBN resolution).
 */
class AddBookViewModelFactory(
    appContainer: AppContainer,
) : AppViewModelFactory<AddBookViewModel>(
        AddBookViewModel::class.java,
        {
            AddBookViewModel(
                addBookByIsbnUseCase = appContainer.addBookByIsbnUseCase,
                searchMediaUseCase = appContainer.searchMediaUseCase,
                searchProvider = appContainer.searchProvider,
                resolveWorkToEditionsUseCase = appContainer.resolveWorkToEditionsUseCase,
            )
        },
    )

/**
 * Factory for creating [BookDetailViewModel] with its [bookId] and repository/use-case
 * dependencies from the [AppContainer]. Unlike [LibraryViewModelFactory]/[AddBookViewModelFactory],
 * this factory is parameterized per-navigation-argument (one instance per book detail route),
 * so it is constructed fresh in the route wrapper rather than reused.
 */
class BookDetailViewModelFactory
    @OptIn(kotlin.time.ExperimentalTime::class)
    constructor(
        appContainer: AppContainer,
        bookId: String,
    ) : AppViewModelFactory<BookDetailViewModel>(
            BookDetailViewModel::class.java,
            {
                BookDetailViewModel(
                    bookId = bookId,
                    mediaRepository = appContainer.mediaRepository,
                    bookRepository = appContainer.bookRepository,
                    readingSessionRepository = appContainer.readingSessionRepository,
                    logReadingSessionUseCase = appContainer.logReadingSessionUseCase,
                    refetchCoverUseCase = appContainer.refetchCoverUseCase,
                    deleteMediaUseCase = appContainer.deleteMediaUseCase,
                )
            },
        )

/**
 * Factory for creating [EditBookViewModel] with its [bookId] and
 * [com.hub.media.features.books.data.BookRepository] dependency from the [AppContainer]
 * (ROADMAP Task 6 Phase A). Per-navigation-argument like [BookDetailViewModelFactory]: constructed
 * fresh in the route wrapper for each `Route.EditBook` navigation rather than reused.
 */
class EditBookViewModelFactory(
    appContainer: AppContainer,
    bookId: String,
) : AppViewModelFactory<EditBookViewModel>(
        EditBookViewModel::class.java,
        {
            EditBookViewModel(
                bookId = bookId,
                bookRepository = appContainer.bookRepository,
            )
        },
    )

/**
 * Factory for creating [StatsViewModel] with its [com.hub.media.features.stats.data.StatsRepository]/
 * [com.hub.media.features.settings.data.SettingsRepository] dependencies from the [AppContainer]
 * (ROADMAP Task 5 Phase C; the settings dependency was added in Task 7 Phase B so `StatsViewModel`
 * can react to the week-start-day preference — see that class's KDoc). Reused across navigations to
 * the stats screen the same way [LibraryViewModelFactory]/[AddBookViewModelFactory] are (unlike the
 * per-navigation-argument [BookDetailViewModelFactory]).
 */
class StatsViewModelFactory
    @OptIn(kotlin.time.ExperimentalTime::class)
    constructor(
        appContainer: AppContainer,
    ) : AppViewModelFactory<StatsViewModel>(
            StatsViewModel::class.java,
            { StatsViewModel(appContainer.statsRepository, appContainer.settingsRepository) },
        )

/**
 * Factory for creating [SettingsViewModel] with its
 * [com.hub.media.features.settings.data.SettingsRepository] dependency from the [AppContainer]
 * (ROADMAP Task 7 Phase B). Reused across navigations to the Settings screen the same way
 * [StatsViewModelFactory]/[LibraryViewModelFactory] are (unlike the per-navigation-argument
 * [BookDetailViewModelFactory]/[EditBookViewModelFactory]).
 */
class SettingsViewModelFactory(
    appContainer: AppContainer,
) : AppViewModelFactory<SettingsViewModel>(
        SettingsViewModel::class.java,
        { SettingsViewModel(appContainer.settingsRepository, appContainer.tmdbClient) },
    )

/**
 * Factory for creating [ExportViewModel] with its
 * [com.hub.media.features.portability.domain.ExportDataUseCase] dependency from the
 * [AppContainer] (ROADMAP Task 8 Phase A). Reused across navigations to the Settings screen the
 * same way [SettingsViewModelFactory] is -- both ViewModels are constructed side by side by the
 * same route composable.
 */
class ExportViewModelFactory(
    appContainer: AppContainer,
) : AppViewModelFactory<ExportViewModel>(
        ExportViewModel::class.java,
        { ExportViewModel(appContainer.exportDataUseCase) },
    )

/**
 * Factory for creating [ImportViewModel] with its
 * [com.hub.media.features.portability.domain.ImportDataUseCase] dependency from the
 * [AppContainer] (ROADMAP Task 8 Phase B). Reused across navigations to the Settings screen the
 * same way [ExportViewModelFactory] is -- both ViewModels are constructed side by side by the
 * same route composable.
 */
class ImportViewModelFactory(
    appContainer: AppContainer,
) : AppViewModelFactory<ImportViewModel>(
        ImportViewModel::class.java,
        { ImportViewModel(appContainer.importDataUseCase) },
    )

/**
 * Factory for creating [BackupViewModel] with its
 * [com.hub.media.features.portability.domain.DatabaseBackupUseCase] dependency from the
 * [AppContainer] (ROADMAP Task 8 Phase C). Reused across navigations to the Settings screen the
 * same way [ExportViewModelFactory] is.
 */
class BackupViewModelFactory(
    appContainer: AppContainer,
) : AppViewModelFactory<BackupViewModel>(
        BackupViewModel::class.java,
        { BackupViewModel(appContainer.backupDatabaseUseCase) },
    )

/**
 * Factory for creating [RestoreViewModel] with its
 * [com.hub.media.features.portability.domain.RestoreDatabaseUseCase] dependency from the
 * [AppContainer] (ROADMAP Task 8 Phase C). Reused across navigations to the Settings screen the
 * same way [ExportViewModelFactory] is -- see [RestoreViewModel]'s KDoc for why it only ever
 * exposes the non-destructive "stage" half of restore, not the destructive swap itself.
 */
class RestoreViewModelFactory(
    appContainer: AppContainer,
) : AppViewModelFactory<RestoreViewModel>(
        RestoreViewModel::class.java,
        { RestoreViewModel(appContainer.restoreDatabaseUseCase, appContainer.settingsRepository) },
    )

/**
 * Factory for creating [BackfillViewModel] with its
 * [com.hub.media.features.books.domain.BulkBackfillUseCase] dependency from the [AppContainer]
 * (ROADMAP Task 14 Phase A). Reused across navigations to the Settings screen the same way
 * [ExportViewModelFactory] is.
 */
class BackfillViewModelFactory(
    appContainer: AppContainer,
) : AppViewModelFactory<BackfillViewModel>(
        BackfillViewModel::class.java,
        { BackfillViewModel(appContainer.bulkBackfillUseCase) },
    )

/**
 * Factory for creating [LogViewerViewModel] with the [com.hub.media.core.storage.LogFileStore]
 * dependency from the [AppContainer] (ROADMAP Task 15 Phase B2). Reused across navigations to the
 * log viewer the same way [SettingsViewModelFactory] is.
 */
class LogViewerViewModelFactory(
    appContainer: AppContainer,
) : AppViewModelFactory<LogViewerViewModel>(
        LogViewerViewModel::class.java,
        { LogViewerViewModel(appContainer.logFileStore) },
    )

/**
 * Factory for [ChangelogViewModel] (ROADMAP Task 15 Phase B2b). Unlike every other factory here it
 * takes no [AppContainer]: the changelog viewer reads an asset and the app's own `versionName`, and
 * touches no repository, database or network at all -- so wiring it through the container would
 * imply a dependency it does not have.
 */
class ChangelogViewModelFactory(
    currentVersion: String,
    readChangelog: suspend () -> String?,
) : AppViewModelFactory<ChangelogViewModel>(
        ChangelogViewModel::class.java,
        { ChangelogViewModel(currentVersion, readChangelog) },
    )

/**
 * Factory for [AddMovieViewModel] (ROADMAP Task 13 Phase B). Manual entry needs only the
 * repository — no provider client, no rate limiter, no cover storage.
 */
class AddMovieViewModelFactory(
    appContainer: AppContainer,
) : AppViewModelFactory<AddMovieViewModel>(
        AddMovieViewModel::class.java,
        { AddMovieViewModel(movieRepository = appContainer.movieRepository) },
    )

/**
 * Factory for [MovieDetailViewModel] with its [movieId] (ROADMAP Task 13 Phase B).
 *
 * Takes [AppContainer.deleteMediaUseCase] rather than the repository's own delete for the same
 * reason [BookDetailViewModelFactory] does: deletion must go through the reference-aware path so a
 * poster shared with another item is not removed out from under it.
 */
class MovieDetailViewModelFactory(
    appContainer: AppContainer,
    movieId: String,
) : AppViewModelFactory<MovieDetailViewModel>(
        MovieDetailViewModel::class.java,
        {
            MovieDetailViewModel(
                movieId = movieId,
                movieRepository = appContainer.movieRepository,
                deleteMediaUseCase = appContainer.deleteMediaUseCase,
            )
        },
    )

/** Factory for [EditMovieViewModel] with its [movieId] (ROADMAP Task 13 Phase B). */
class EditMovieViewModelFactory(
    appContainer: AppContainer,
    movieId: String,
) : AppViewModelFactory<EditMovieViewModel>(
        EditMovieViewModel::class.java,
        {
            EditMovieViewModel(
                movieId = movieId,
                movieRepository = appContainer.movieRepository,
            )
        },
    )

/**
 * Factory for [AddTVShowViewModel] (ROADMAP Task 13 Phase C). Manual entry needs only the
 * repository, mirroring [AddMovieViewModelFactory] -- no provider client, no rate limiter, no
 * cover storage.
 */
class AddTVShowViewModelFactory(
    appContainer: AppContainer,
) : AppViewModelFactory<AddTVShowViewModel>(
        AddTVShowViewModel::class.java,
        { AddTVShowViewModel(tvShowRepository = appContainer.tvShowRepository) },
    )

/**
 * Factory for [MovieSearchViewModel] (ROADMAP Task 13 Phase D) -- the film counterpart of
 * [TVShowSearchViewModelFactory], and needing the same two dependencies for the same reasons.
 */
class MovieSearchViewModelFactory(
    appContainer: AppContainer,
) : AppViewModelFactory<MovieSearchViewModel>(
        MovieSearchViewModel::class.java,
        {
            MovieSearchViewModel(
                tmdbClient = appContainer.tmdbClient,
                movieRepository = appContainer.movieRepository,
            )
        },
    )

/**
 * Factory for [TVShowSearchViewModel] (ROADMAP Task 13 Phase D). Unlike [AddTVShowViewModelFactory]
 * this one needs the provider client as well, because the search path is the only add path that
 * talks to TMDB. `appContainer.tmdbClient` reads its credential per request, so a key entered after
 * this factory ran is still picked up -- see [AppContainer] on why it is never captured.
 */
class TVShowSearchViewModelFactory(
    appContainer: AppContainer,
) : AppViewModelFactory<TVShowSearchViewModel>(
        TVShowSearchViewModel::class.java,
        {
            TVShowSearchViewModel(
                tmdbClient = appContainer.tmdbClient,
                tvShowRepository = appContainer.tvShowRepository,
            )
        },
    )

/**
 * Factory for [TVShowDetailViewModel] with its [showId] (ROADMAP Task 13 Phase C).
 *
 * Takes [AppContainer.deleteMediaUseCase] rather than the repository's own delete, mirroring
 * [MovieDetailViewModelFactory]: deletion must go through the reference-aware path so a poster
 * shared with another item is not removed out from under it.
 */
class TVShowDetailViewModelFactory(
    appContainer: AppContainer,
    showId: String,
) : AppViewModelFactory<TVShowDetailViewModel>(
        TVShowDetailViewModel::class.java,
        {
            TVShowDetailViewModel(
                showId = showId,
                tvShowRepository = appContainer.tvShowRepository,
                deleteMediaUseCase = appContainer.deleteMediaUseCase,
                backfillUseCase = appContainer.backfillShowEpisodesUseCase,
            )
        },
    )
