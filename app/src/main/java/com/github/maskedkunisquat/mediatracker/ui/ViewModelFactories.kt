package com.github.maskedkunisquat.mediatracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hub.media.ui.AddBookViewModel
import com.hub.media.ui.AppContainer
import com.hub.media.ui.BookDetailViewModel
import com.hub.media.ui.EditBookViewModel
import com.hub.media.ui.ExportViewModel
import com.hub.media.ui.LibraryViewModel
import com.hub.media.ui.SettingsViewModel
import com.hub.media.ui.StatsViewModel

/**
 * Factory for creating [LibraryViewModel] with its [com.hub.media.features.books.data.BookRepository]
 * dependency from the [AppContainer].
 */
class LibraryViewModelFactory(private val appContainer: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(LibraryViewModel::class.java) -> {
                LibraryViewModel(appContainer.bookRepository) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
    }
}

/**
 * Factory for creating [AddBookViewModel] with its [com.hub.media.features.books.domain.AddBookByIsbnUseCase]
 * dependency from the [AppContainer].
 */
class AddBookViewModelFactory(private val appContainer: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(AddBookViewModel::class.java) -> {
                AddBookViewModel(appContainer.addBookByIsbnUseCase) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
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
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(BookDetailViewModel::class.java) -> {
                BookDetailViewModel(
                    bookId = bookId,
                    bookRepository = appContainer.bookRepository,
                    readingSessionRepository = appContainer.readingSessionRepository,
                    logReadingSessionUseCase = appContainer.logReadingSessionUseCase,
                    refetchCoverUseCase = appContainer.refetchCoverUseCase,
                ) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
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
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(EditBookViewModel::class.java) -> {
                EditBookViewModel(
                    bookId = bookId,
                    bookRepository = appContainer.bookRepository,
                ) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
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
class StatsViewModelFactory(private val appContainer: AppContainer) : ViewModelProvider.Factory {
    @OptIn(kotlin.time.ExperimentalTime::class)
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(StatsViewModel::class.java) -> {
                StatsViewModel(appContainer.statsRepository, appContainer.settingsRepository) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
    }
}

/**
 * Factory for creating [SettingsViewModel] with its
 * [com.hub.media.features.settings.data.SettingsRepository] dependency from the [AppContainer]
 * (ROADMAP Task 7 Phase B). Reused across navigations to the Settings screen the same way
 * [StatsViewModelFactory]/[LibraryViewModelFactory] are (unlike the per-navigation-argument
 * [BookDetailViewModelFactory]/[EditBookViewModelFactory]).
 */
class SettingsViewModelFactory(private val appContainer: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
                SettingsViewModel(appContainer.settingsRepository) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
    }
}

/**
 * Factory for creating [ExportViewModel] with its
 * [com.hub.media.features.portability.domain.ExportDataUseCase] dependency from the
 * [AppContainer] (ROADMAP Task 8 Phase A). Reused across navigations to the Settings screen the
 * same way [SettingsViewModelFactory] is -- both ViewModels are constructed side by side by the
 * same route composable.
 */
class ExportViewModelFactory(private val appContainer: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(ExportViewModel::class.java) -> {
                ExportViewModel(appContainer.exportDataUseCase) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
    }
}
