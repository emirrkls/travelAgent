package com.emirrkls.phokarta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.emirrkls.phokarta.ui.PhokartaApp
import com.emirrkls.phokarta.ui.theme.PhokartaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhokartaTheme { PhokartaApp() }
        }
    }
}
