package com.metrolist.shared

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Entry point called from Swift (iosApp) to obtain a UIViewController that
 * hosts the shared Compose Multiplatform UI tree defined in [App].
 */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
