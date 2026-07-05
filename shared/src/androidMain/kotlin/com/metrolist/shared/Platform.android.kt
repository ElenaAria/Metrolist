package com.metrolist.shared

import android.os.Build

actual fun platformName(): String = "Android ${Build.VERSION.SDK_INT}"
