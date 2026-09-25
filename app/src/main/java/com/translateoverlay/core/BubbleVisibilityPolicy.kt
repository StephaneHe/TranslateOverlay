package com.translateoverlay.core

/** Decides whether the floating bubble must be shown for the current foreground app. */
object BubbleVisibilityPolicy {

    /**
     * @param current current visibility, kept when the foreground package is unknown or transient
     *   (keyboard, system UI…) so the bubble does not flicker.
     */
    fun shouldShow(
        bubbleEnabled: Boolean,
        foregroundPackage: String?,
        excludedPackages: Set<String>,
        ownPackage: String,
        transientPackages: Set<String>,
        current: Boolean,
    ): Boolean {
        if (!bubbleEnabled) return false
        if (foregroundPackage.isNullOrEmpty() || foregroundPackage in transientPackages) return current
        if (foregroundPackage == ownPackage) return false
        return foregroundPackage !in excludedPackages
    }
}
