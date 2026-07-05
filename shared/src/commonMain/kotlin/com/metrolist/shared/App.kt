package com.metrolist.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Proof-of-concept shared Compose Multiplatform entry point.
 *
 * This composable is rendered natively on both Android (embedded directly
 * in a Compose hierarchy) and iOS (hosted through ComposeUIViewController,
 * see [MainViewController] in iosMain). It exists to validate that the
 * Kotlin Multiplatform + Compose Multiplatform toolchain builds end-to-end
 * on both platforms before real shared business logic/UI is migrated here.
 */
@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Agonis")
                Text("Kotlin Multiplatform preview – running on ${platformName()}")
            }
        }
    }
}
