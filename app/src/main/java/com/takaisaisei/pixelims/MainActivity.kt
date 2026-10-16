package com.takaisaisei.pixelims

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.takaisaisei.pixelims.ui.MainScreen
import com.takaisaisei.pixelims.ui.MainViewModel
import com.takaisaisei.pixelims.ui.theme.PixelIMSTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == SHIZUKU_REQUEST_CODE) viewModel.refreshAuthorization()
    }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        viewModel.refreshAuthorization()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        viewModel.refreshAuthorization()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        enableEdgeToEdge()
        setContent {
            PixelIMSTheme {
                MainScreen(onRequestShizuku = ::onRequestShizuku, viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
        viewModel.refreshAuthorization()
    }

    override fun onStop() {
        super.onStop()
        viewModel.setForeground(false)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    private fun onRequestShizuku() {
        if (!Shizuku.pingBinder()) {
            toast(getString(R.string.msg_shizuku_unavailable))
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            viewModel.refreshAuthorization()
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private companion object {
        const val SHIZUKU_REQUEST_CODE = 1001
    }
}
