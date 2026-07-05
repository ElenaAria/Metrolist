package com.metrolist.shared

/**
 * Identifies the platform this shared module code is currently running on.
 * Implemented per-target in androidMain / iosMain (expect/actual).
 */
expect fun platformName(): String
