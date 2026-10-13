package com.vpn.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {

    // با هر برگشت به برنامه یک واحد زیاد می‌شود تا وضعیت اجازه‌ی اعلان دوباره چک شود
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppRoot(resumeTick)
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}
