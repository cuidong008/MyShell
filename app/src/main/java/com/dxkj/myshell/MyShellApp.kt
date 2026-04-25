package com.dxkj.myshell

import android.app.Application
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
    }
}

