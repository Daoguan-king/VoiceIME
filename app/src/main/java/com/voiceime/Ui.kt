package com.voiceime

import android.content.Context

/** dp 转 px（Activity/Service 共用，避免重复实现） */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
