package com.github.maskedkunisquat.mediatracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hub.media.ui.AddBookViewModel
import com.hub.media.ui.AppContainer
import com.hub.media.ui.BookDetailViewModel
import com.hub.media.ui.LibraryViewModel

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
                ) as T
            }
            else -> error("Unknown viewmodel class: $modelClass")
        }
    }
}
