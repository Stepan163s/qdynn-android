package com.qdynn.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    companion object {
        private const val REQUEST_VPN = 1001
    }

    private lateinit var editDomain: EditText
    private lateinit var editPassword: EditText
    private lateinit var editDns: EditText
    private lateinit var btnToggle: Button
    private lateinit var textStatus: TextView

    private var isVpnRunning: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editDomain = findViewById(R.id.editDomain)
        editPassword = findViewById(R.id.editPassword)
        editDns = findViewById(R.id.editDns)
        btnToggle = findViewById(R.id.btnToggle)
        textStatus = findViewById(R.id.textStatus)

        btnToggle.setOnClickListener {
            if (!isVpnRunning) {
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, REQUEST_VPN)
                } else {
                    startVpn()
                }
            } else {
                stopVpn()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            if (resultCode == Activity.RESULT_OK) {
                startVpn()
            } else {
                updateStatus("Разрешение VPN отклонено")
            }
        }
    }

    private fun startVpn() {
        val domain = editDomain.text?.toString()?.trim().orEmpty()
        val password = editPassword.text?.toString().orEmpty()
        val dns = editDns.text?.toString()?.trim().orEmpty()

        if (domain.isEmpty() || password.isEmpty() || dns.isEmpty()) {
            updateStatus("Укажите домен, пароль и DNS")
            return
        }

        val serviceIntent = Intent(this, QdynnVpnService::class.java).apply {
            action = QdynnVpnService.ACTION_START
            putExtra(QdynnVpnService.EXTRA_DOMAIN, domain)
            putExtra(QdynnVpnService.EXTRA_PASSWORD, password)
            putExtra(QdynnVpnService.EXTRA_DNS, dns)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        isVpnRunning = true
        btnToggle.text = "Остановить VPN"
        updateStatus("Подключено: $domain через DNS $dns")
        setInputsEnabled(false)
    }

    private fun stopVpn() {
        val stopIntent = Intent(this, QdynnVpnService::class.java).apply {
            action = QdynnVpnService.ACTION_STOP
        }
        startService(stopIntent)

        isVpnRunning = false
        btnToggle.text = "Запустить VPN"
        updateStatus("Статус: отключено")
        setInputsEnabled(true)
    }

    private fun setInputsEnabled(enabled: Boolean) {
        editDomain.isEnabled = enabled
        editPassword.isEnabled = enabled
        editDns.isEnabled = enabled
    }

    private fun updateStatus(text: String) {
        textStatus.text = text
    }
}