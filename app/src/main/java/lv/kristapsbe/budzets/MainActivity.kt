package lv.kristapsbe.budzets

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import lv.kristapsbe.budzets.ui.navigation.BudzetsApp
import lv.kristapsbe.budzets.ui.theme.BudzetsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BudzetsTheme {
                BudzetsApp()
            }
        }
    }
}
