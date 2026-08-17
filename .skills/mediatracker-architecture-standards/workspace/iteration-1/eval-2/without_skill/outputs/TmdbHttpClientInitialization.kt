/**
 * TMDB API Client Initialization - Adhering to MediaTracker Architecture Standards
 * 
 * Standards followed:
 * - Ktor Client with kotlinx.serialization
 * - User-Agent identification (inherited from createHttpClient)
 * - Optional, user-provided API key from app_settings
 * - CancellationException rethrowing for structured concurrency
 * - Manual DI via AppContainer wiring
 */

// 1. Settings Repository extension for TMDB API Key (ProviderApiKeys.kt)
internal const val KEY_TMDB_API_KEY = "tmdb_api_key"

public suspend fun SettingsRepository.getTmdbApiKey(): String? =
    getString(KEY_TMDB_API_KEY)?.trim()?.ifBlank { null }

// 2. TMDB Client Implementation (features/movies/network/TmdbClient.kt)
private const val TMDB_BASE_URL = "https://api.themoviedb.org/3"

public class TmdbClient(
    private val client: HttpClient,
    private val apiKeyProvider: suspend () -> String? = { null },
    private val logger: Logger = AppLogger,
) {
    /** Example: Fetch movie details by TMDB ID */
    suspend fun fetchMovieDetails(movieId: Int): Resource<MovieMetadata> {
        return try {
            val apiKey = apiKeyProvider()
            val response = client.get("$TMDB_BASE_URL/movie/$movieId") {
                // TMDB requires api_key as a query parameter (or Bearer token)
                if (apiKey != null) {
                    parameter("api_key", apiKey)
                }
            }

            if (!response.status.isSuccess()) {
                logger.warn("TmdbClient") { 
                    "TMDB request failed with status ${response.status.value} for movie=$movieId" 
                }
                return Resource.Error("TMDB request failed: ${response.status.value}")
            }

            val dto = response.body<TmdbMovieDto>()
            Resource.Success(dto.toDomain())
        } catch (e: CancellationException) {
            throw e // Essential for Compose/Lifecycle cancellation
        } catch (e: Exception) {
            logger.warn("TmdbClient", e) { "TMDB lookup failed for movie=$movieId" }
            Resource.Error("TMDB lookup failed: ${e.message}")
        }
    }
}

// 3. AppContainer Wiring (ui/AppContainer.kt)
public class AppContainer {
    private val httpClient = createHttpClient()
    private val settingsRepository = SettingsRepository(database.appSettingsDao())

    private val tmdbApiKeyProvider: suspend () -> String? = {
        settingsRepository.getTmdbApiKey()
    }

    public val tmdbClient: TmdbClient = TmdbClient(
        client = httpClient,
        apiKeyProvider = tmdbApiKeyProvider,
        logger = AppLogger
    )
}
