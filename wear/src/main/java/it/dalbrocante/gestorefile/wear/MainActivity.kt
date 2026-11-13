package it.dalbrocante.gestorefile.wear

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: WatchVm by viewModels()

    private fun isStem(code: Int) =
        code == KeyEvent.KEYCODE_STEM_PRIMARY || code == KeyEvent.KEYCODE_STEM_1 ||
            code == KeyEvent.KEYCODE_STEM_2 || code == KeyEvent.KEYCODE_STEM_3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!vm.back()) finish() }
        })
        vm.permRequester = {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        setContent { WatchApp(vm) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        vm.refreshStatus()
        vm.open(vm.root.takeIf { it.canRead() } ?: return)
    }

    override fun onResume() { super.onResume(); vm.refreshStatus() }

    // Pulsanti fisici: pressione breve e lunga distinte.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isStem(keyCode)) { event.startTracking(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isStem(keyCode)) { vm.onKey(keyCode, true); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isStem(keyCode)) {
            if (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS == 0) {
                vm.onKey(keyCode, false)
                if (keyCode == KeyEvent.KEYCODE_STEM_2 && vm.dir == null && vm.menu == null) finish()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
