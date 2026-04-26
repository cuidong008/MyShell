package com.dxkj.myshell

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class MyShellApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Android ships a stripped "BC" provider. sshj needs modern algorithms (e.g. X25519),
        // so we replace it with the full BouncyCastle provider.
        try {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (_: Throwable) {
        }

        // Background transfer notifications
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(
                        "transfer",
                        "文件传输",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            } catch (_: Throwable) {
            }
        }
    }
}

