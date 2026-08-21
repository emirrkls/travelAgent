package com.emirrkls.travelagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.emirrkls.travelagent.ui.TravelAgentApp
import com.emirrkls.travelagent.ui.theme.TravelAgentTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TravelAgentTheme { TravelAgentApp() }
        }
    }
}
