package com.multicast.bara

import android.content.Context
import org.json.JSONException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Persist message keys, then resolve them using the current display language. */
enum class ArtworkMessage(val resource: Int) {
    EMPTY(R.string.status_empty),
    SEARCHING(R.string.status_searching),
    UPDATED(R.string.status_updated),
    CROP_APPLIED(R.string.status_crop_applied),
    CROP_STALE(R.string.status_crop_stale),
    CANCELLED(R.string.status_cancelled),
    SAVE_FAILED(R.string.error_image_save),
    NO_RESULTS(R.string.status_no_results),
    UPDATE_FAILED(R.string.status_update_failed),
    TAG_COUNT(R.string.error_tag_count),
    TAG_SYNTAX(R.string.error_tag_syntax),
    TAG_REQUIRED(R.string.error_tag_required),
    TAG_CONFLICT(R.string.error_tag_conflict),
    URL(R.string.error_url),
    REDIRECT(R.string.error_redirect),
    HTTP(R.string.error_http),
    FILE_TOO_LARGE(R.string.error_file_too_large),
    IMAGE_FORMAT(R.string.error_image_format),
    TIMEOUT(R.string.error_timeout),
    REDIRECT_LIMIT(R.string.error_redirect_limit),
    STORAGE(R.string.error_storage),
    IMAGE_SIZE(R.string.error_image_size),
    IMAGE_ASPECT(R.string.error_image_aspect),
    IMAGE_CORRUPT(R.string.error_image_corrupt),
    IMAGE_PROCESSING(R.string.error_image_processing),
    RESPONSE(R.string.error_response),
    NETWORK(R.string.error_network),
    UNKNOWN(R.string.error_unknown)
}

val TagQueryError.message: ArtworkMessage get() = when (this) {
    TagQueryError.COUNT -> ArtworkMessage.TAG_COUNT
    TagQueryError.SYNTAX -> ArtworkMessage.TAG_SYNTAX
    TagQueryError.REQUIRED -> ArtworkMessage.TAG_REQUIRED
    TagQueryError.CONFLICT -> ArtworkMessage.TAG_CONFLICT
}

class ArtworkException(val problem: ArtworkMessage, vararg val arguments: String) : IOException(problem.name)

data class ArtworkFailure(val message: ArtworkMessage, val arguments: List<String> = emptyList()) {
    fun text(context: Context): String = context.getString(message.resource, *arguments.toTypedArray())

    companion object {
        fun from(error: Exception): ArtworkFailure = when (error) {
            is ArtworkException -> ArtworkFailure(error.problem, error.arguments.toList())
            is SocketTimeoutException -> ArtworkFailure(ArtworkMessage.TIMEOUT)
            is UnknownHostException -> ArtworkFailure(ArtworkMessage.NETWORK)
            is JSONException -> ArtworkFailure(ArtworkMessage.RESPONSE)
            is IOException -> ArtworkFailure(ArtworkMessage.NETWORK)
            else -> ArtworkFailure(ArtworkMessage.UNKNOWN)
        }
    }
}
