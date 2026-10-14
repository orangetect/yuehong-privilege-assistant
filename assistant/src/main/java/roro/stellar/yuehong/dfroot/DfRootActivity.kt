package roro.stellar.yuehong.dfroot

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import roro.stellar.yuehong.ui.DfRootScreen
import roro.stellar.yuehong.ui.StellarTheme

class DfRootActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            StellarTheme {
                DfRootScreen(onBack = ::finish)
            }
        }
    }
}
