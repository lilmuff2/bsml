package lilmuff1.bsml.ui

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import lilmuff1.bsml.logging.VpnLogRepository
import lilmuff1.bsml.service.AssetProxyService
import lilmuff1.bsml.service.LocalVpnService
import lilmuff1.bsml.ui.theme.BSMLTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BSMLTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isVpnRunning by VpnLogRepository.isRunning.collectAsState()
    val isAssetProxyRunning by VpnLogRepository.isAssetProxyRunning.collectAsState()
    val isRunning = isVpnRunning || isAssetProxyRunning

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == RESULT_OK) {
            startMonitoring(context)
        } else {
            VpnLogRepository.setStatus("VPN permission denied")
            VpnLogRepository.log("VPN permission denied by user")
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    if (isRunning) {
                        stopMonitoring(context)
                    } else {
                        val permissionIntent = VpnService.prepare(context)
                        if (permissionIntent == null) {
                            startMonitoring(context)
                        } else {
                            vpnPermissionLauncher.launch(permissionIntent)
                        }
                    }
                }
            ) {
                Text(
                    text = if (isRunning) "STOP" else "START",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                )
            }
        }
    }
}

private fun startMonitoring(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, AssetProxyService::class.java)
            .setAction(AssetProxyService.ACTION_START)
    )
    ContextCompat.startForegroundService(
        context,
        Intent(context, LocalVpnService::class.java)
            .setAction(LocalVpnService.ACTION_START)
    )
}

private fun stopMonitoring(context: Context) {
    context.startService(
        Intent(context, LocalVpnService::class.java).setAction(LocalVpnService.ACTION_STOP)
    )
    context.startService(
        Intent(context, AssetProxyService::class.java).setAction(AssetProxyService.ACTION_STOP)
    )
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    BSMLTheme {
        MainScreen()
    }
}
