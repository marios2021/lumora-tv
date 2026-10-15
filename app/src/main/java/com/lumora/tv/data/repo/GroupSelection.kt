package com.lumora.tv.data.repo

/** Which slice of a playlist is being browsed. Also the zapping order in the player. */
sealed interface GroupSelection {
    data object Favorites : GroupSelection
    data object All : GroupSelection
    data class Named(val name: String) : GroupSelection

    /** Compact form for navigation arguments. */
    fun encode(): String = when (this) {
        Favorites -> "fav"
        All -> "all"
        is Named -> "g:$name"
    }

    companion object {
        fun decode(value: String?): GroupSelection = when {
            value == "fav" -> Favorites
            value != null && value.startsWith("g:") -> Named(value.removePrefix("g:"))
            else -> All
        }
    }
}
