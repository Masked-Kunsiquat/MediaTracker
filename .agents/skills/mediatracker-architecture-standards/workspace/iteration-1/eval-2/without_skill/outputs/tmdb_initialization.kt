/**
 * Ktor HttpClient initialization pattern for a new TMDB service in MediaTracker.
 * Following AGENTS.md and shared module architectural standards.
 */

// 1. Dependency in AppContainer
private val httpClient = createHttpClient()

// 2. Secure API Key Provider
private val tmdbApiKeyProvider: suspend () -> String? = {
    settingsRepository.getTmdbApiKey()
}

// 3. Client instantiation with shared HttpClient
public val tmdbClient: TmdbClient = TmdbClient(
    client = httpClient,
    apiKeyProvider = tmdbApiKeyProvider
)

/**
 * Service implementation demonstrating proper Ktor usage:
 * - Uses shared HttpClient
 * - Handles optional API key via provider
 * - Rethrows CancellationException
 * - Maps results to Resource<T>
 */
public class TmdbClient(
    private val client: HttpClient,
    private val apiKeyProvider: suspend () -> String? = { null },
    private val logger: Logger = AppLogger,
) {
    suspend fun fetchMovie(tmdbId: Int): Resource<TmdbMovieDto> {
        return try {
            val apiKey = apiKeyProvider() ?: return Resource.Error("TMDB API key is missing")
            
            val response = client.get("https://api.themoviedb.org/3/movie/$tmdbId") {
                bearerAuth(apiKey)
            }

            if (!response.status.isSuccess()) {
                logger.warn("TmdbClient") { "TMDB lookup returned ${response.status.value}" }
                return Resource.Error("TMDB request failed with status ${response.status.value}")
            }

            Resource.Success(response.body<TmdbMovieDto>())
        } catch (e: CancellationException) {
            throw e 
        } catch (e: Exception) {
            logger.warn("TmdbClient", e) { "TMDB lookup failed" }
            Resource.Error("TMDB lookup failed: ${e.message}", e)
        }
    }
}
