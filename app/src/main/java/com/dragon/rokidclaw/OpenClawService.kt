package com.dragon.rokidclaw

import android.app.Service
import android.content.Intent
import android.os.IBinder

class OpenClawService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}