package com.implacavel.alarme

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri

/** Cada permissão que o alarme precisa pra ser impossível de ignorar. */
enum class Poder(val titulo: String, val explicacao: String) {
    NOTIFICACOES("Notificações", "Sem isso o alarme não consegue aparecer."),
    ALARME_EXATO("Alarme no minuto exato", "Toca na hora certa mesmo com o celular dormindo."),
    TELA_CHEIA("Tela cheia", "Deixa o alarme tomar a tela, mesmo bloqueada."),
    CANAL("Alerta em destaque", "O canal \"Alarme tocando\" precisa estar com importância alta."),
    BATERIA("Bateria sem restrição", "Impede o celular de colocar o app pra dormir."),
}

object Poderes {
    fun liberado(ctx: Context, poder: Poder): Boolean = when (poder) {
        Poder.NOTIFICACOES -> NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        Poder.ALARME_EXATO ->
            Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        Poder.TELA_CHEIA ->
            Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        Poder.CANAL -> {
            val canal = ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(Notificacoes.CANAL_ALARME)
            canal == null || canal.importance >= NotificationManager.IMPORTANCE_HIGH
        }
        Poder.BATERIA -> ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** Tela das Configurações onde esse poder é liberado. */
    @SuppressLint("BatteryLife", "InlinedApi")
    fun telaParaLiberar(ctx: Context, poder: Poder): Intent {
        val pacote = "package:${ctx.packageName}".toUri()
        return when (poder) {
            Poder.NOTIFICACOES -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            Poder.ALARME_EXATO -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pacote)
            Poder.TELA_CHEIA -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pacote)
            Poder.CANAL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Notificacoes.CANAL_ALARME)
            Poder.BATERIA -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pacote)
        }
    }
}
