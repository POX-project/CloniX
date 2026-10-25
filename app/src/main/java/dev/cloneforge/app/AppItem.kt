package dev.cloneforge.app

import android.graphics.drawable.Drawable

/** Immutable data holder for one row of the installed-app list. */
data class AppItem(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    val isSystem: Boolean
)
