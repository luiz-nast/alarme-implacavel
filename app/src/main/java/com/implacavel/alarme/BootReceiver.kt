package com.implacavel.alarme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * O Android apaga os agendamentos quando o celular reinicia. Este receiver agenda tudo de novo,
 * inclusive antes do primeiro desbloqueio (LOCKED_BOOT_COMPLETED), e também quando o relógio ou o
 * fuso mudam, o app é atualizado ou a permissão de alarme exato muda.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action in ACOES) Agendador.reagendarTodos(ctx)
    }

    private companion object {
        val ACOES = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
