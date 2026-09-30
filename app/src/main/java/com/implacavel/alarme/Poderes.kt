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
 * Cada permissão que o alarme precisa, com a checagem ([liberado]) e o jeito de liberar.
 * [permissao] é a permissão pedida com o diálogo do sistema, quando existe; as demais, e as já
 * negadas no diálogo, são liberadas numa tela das Configurações ([abrirConfiguracao]).
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
    BATERIA("Bateria sem restrição", "Impede o celular de colocar o app pra dormir.");

    fun liberado(ctx: Context): Boolean = when (this) {
        NOTIFICACOES -> NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        MICROFONE, CAMERA -> permissaoFaltando(ctx) == null
        ALARME_EXATO ->
            Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        TELA_CHEIA ->
            Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        CANAL -> {
            val canal = ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(Notificacoes.CANAL_ALARME)
            canal == null || canal.importance >= NotificationManager.IMPORTANCE_HIGH
        }
        BATERIA -> ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** A permissão do diálogo do sistema, se ainda falta; null se já foi dada ou se este poder não tem diálogo. */
    fun permissaoFaltando(ctx: Context): String? =
        permissao?.takeUnless { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    /** Abre a tela das Configurações onde este poder é liberado; se o celular não tiver essa tela, a do app. */
    fun abrirConfiguracao(ctx: Context) {
        runCatching { ctx.startActivity(telaParaLiberar(ctx)) }
            .onFailure { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pacote(ctx))) }
    }

    @SuppressLint("BatteryLife", "InlinedApi")
    private fun telaParaLiberar(ctx: Context): Intent = when (this) {
        NOTIFICACOES -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        MICROFONE, CAMERA -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pacote(ctx))
        ALARME_EXATO -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pacote(ctx))
        TELA_CHEIA -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pacote(ctx))
        CANAL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, Notificacoes.CANAL_ALARME)
        BATERIA -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pacote(ctx))
    }

    private fun pacote(ctx: Context) = "package:${ctx.packageName}".toUri()
}
