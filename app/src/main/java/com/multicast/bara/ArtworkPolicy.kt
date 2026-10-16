package com.multicast.bara

/** Rating meanings are source-specific: Danbooru 's' is NOT safe. */
enum class ArtworkRating {
    GENERAL, SENSITIVE, QUESTIONABLE, EXPLICIT;

    companion object {
        fun parse(source: ArtworkSource, raw: String): ArtworkRating? = when (source) {
            ArtworkSource.DANBOORU -> when (raw) {
                "g" -> GENERAL
                "s" -> SENSITIVE
                "q" -> QUESTIONABLE
                "e" -> EXPLICIT
                else -> null
            }
            ArtworkSource.SAFEBOORU -> if (raw == "s" || raw == "safe") GENERAL else null
        }
    }
}

enum class ArtworkSource(val label: String, val host: String) {
    DANBOORU("Danbooru", "danbooru.donmai.us"),
    SAFEBOORU("Safebooru", "safebooru.org")
}

data class ArtworkPost(
    val source: ArtworkSource,
    val id: String,
    val fingerprint: String,
    val rating: ArtworkRating,
    val tags: Set<String>,
    val imageUrl: String,
    val pageUrl: String,
    val artist: String,
    val width: Int,
    val height: Int
)

enum class TagQueryError { COUNT, SYNTAX, REQUIRED, CONFLICT }

class TagQueryException(val reason: TagQueryError) : IllegalArgumentException(reason.name)

/** Only exact tags and -excluded_tags. Metatags cannot override the rating policy. */
data class TagQuery(val required: Set<String>, val excluded: Set<String>) {
    val text: String get() = (required + excluded.map { "-$it" }).joinToString(" ")
    fun matches(tags: Set<String>) = tags.containsAll(required) && excluded.none(tags::contains)

    companion object {
        fun parse(text: String): TagQuery {
            val tokens = text.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
            validate(tokens.size in 1..8, TagQueryError.COUNT)
            validate(tokens.all { it.length <= 100 && it.matches(Regex("-?[a-z0-9_()/.'+!-]+")) }, TagQueryError.SYNTAX)
            val required = tokens.filterNot { it.startsWith('-') }.toSet()
            val excluded = tokens.filter { it.startsWith('-') }.map { it.drop(1) }.toSet()
            validate(required.isNotEmpty() && excluded.none { it.isBlank() || it.startsWith('-') }, TagQueryError.REQUIRED)
            validate(required.intersect(excluded).isEmpty(), TagQueryError.CONFLICT)
            return TagQuery(required, excluded)
        }

        private fun validate(condition: Boolean, reason: TagQueryError) {
            if (!condition) throw TagQueryException(reason)
        }
    }
}

object ArtworkPolicy {
    private val excludedTags = setOf(
        "loli", "lolicon", "shota", "shotacon", "child", "children", "toddler", "baby",
        "underage", "minor", "young", "teen", "teenage", "age_regression",
        "young_boy", "young_girl", "child_focus", "child_only", "guro", "gore"
    )

    fun accepts(
        post: ArtworkPost,
        query: TagQuery,
        ceiling: ArtworkRating,
        aspect: ArtworkAspect = ArtworkAspect.ANY,
        screenRatio: Double = 16.0 / 9.0
    ): Boolean =
        post.rating.ordinal <= ceiling.ordinal && query.matches(post.tags) &&
            post.tags.none(excludedTags::contains) && post.width >= 600 && post.height >= 600 &&
            aspect.accepts(post.width, post.height, screenRatio)
}
