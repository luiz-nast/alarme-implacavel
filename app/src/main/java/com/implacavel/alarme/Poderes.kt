package com.implacavel.alarme

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Cada permissão que o alarme precisa. [permissao] é a permissão pedida com o diálogo do sistema,
 * quando existe; as demais são liberadas numa tela das Configurações ([Poderes.telaParaLiberar]).
 */
enum class Poder(val titulo: String, val explicacao: String, val permissao: String? = null) {
    NOTIFICACOES(
        "Notificações",
        "Sem isso o alarme não consegue aparecer.",
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
    ),
    MICROFONE("Microfone", "Pra ouvir você dizer STOP.", Manifest.permission.RECORD_AUDIO),
    CAMERA("Câmera", "Pra conferir que você está de olhos abertos.", Manifest.permission.CAMERA),
    ALARME_EXATO("Alarme no minuto exato", "Toca na hora certa mesmo com o celular dormindo."),
    TELA_CHEIA("Tela cheia", "Deixa o alarme tomar a tela, mesmo bloqueada."),
    CANAL("Alerta em destaque", "O canal \"Alarme tocando\" precisa estar com importância alta."),
    BATERIA("Bateria sem restrição", "Impede o celular de colocar o app pra dormir."),
}

object Poderes {
    fun concedida(ctx: Context, permissao: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permissao) == PackageManager.PERMISSION_GRANTED

    fun liberado(ctx: Context, poder: Poder): Boolean = when (poder) {
        Poder.NOTIFICACOES -> NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        Poder.MICROFONE -> concedida(ctx, Manifest.permission.RECORD_AUDIO)
        Poder.CAMERA -> concedida(ctx, Manifest.permission.CAMERA)
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

    /** Tela das Configurações onde esse poder é liberado (pras permissões já negadas no diálogo, a do app). */
    @SuppressLint("BatteryLife", "InlinedApi")
    fun telaParaLiberar(ctx: Context, poder: Poder): Intent {
        val pacote = "package:${ctx.packageName}".toUri()
        return when (poder) {
            Poder.NOTIFICACOES -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            Poder.MICROFONE, Poder.CAMERA -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pacote)
            Poder.ALARME_EXATO -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pacote)
            Poder.TELA_CHEIA -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pacote)
            Poder.CANAL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Notificacoes.CANAL_ALARME)
            Poder.BATERIA -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pacote)
        }
    }
}
