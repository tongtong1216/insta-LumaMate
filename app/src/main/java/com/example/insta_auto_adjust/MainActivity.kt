package com.example.insta_auto_adjust

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.insta_auto_adjust.presentation.CameraUiState
import com.example.insta_auto_adjust.presentation.ConnectionStatus
import com.example.insta_auto_adjust.presentation.DataSource
import com.example.insta_auto_adjust.ui.screen.ConnectionScreen
import com.example.insta_auto_adjust.ui.theme.InstaAutoAdjustTheme

class MainActivity : ComponentActivity() {

    private var cameraState by mutableStateOf(
        CameraUiState()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            InstaAutoAdjustTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->

                    ConnectionScreen(
                        cameraState = cameraState,
                        onConnectClick = {
                            handleMockConnection()
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun handleMockConnection() {
        when (cameraState.connectionStatus) {

            ConnectionStatus.DISCONNECTED,
            ConnectionStatus.ERROR -> {
                cameraState = cameraState.copy(
                    connectionStatus = ConnectionStatus.CONNECTING,
                    errorMessage = null
                )
            }

            ConnectionStatus.CONNECTING -> {
                cameraState = cameraState.copy(
                    connectionStatus = ConnectionStatus.CONNECTED,
                    mode = "VIDEO",
                    currentEv = 0.0,
                    supportedEv = listOf(
                        -2.0,
                        -1.0,
                        0.0,
                        1.0,
                        2.0
                    ),
                    dataSource = DataSource.MOCK
                )
            }

            ConnectionStatus.CONNECTED -> {
                // 下一阶段：进入 ShootingScreen
            }
        }
    }
}