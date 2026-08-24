package com.emirrkls.phokarta

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.emirrkls.phokarta.ui.PhokartaApp
import com.emirrkls.phokarta.ui.theme.PhokartaTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * AppCompatActivity so [androidx.appcompat.app.AppCompatDelegate.setApplicationLocales]
 * recreates the activity with the selected per-app locale (EN / TR / system default).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhokartaTheme { PhokartaApp() }
        }
    }
}
