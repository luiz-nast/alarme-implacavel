package com.implacavel.alarme

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZonedDateTime

/**
 * Agenda e cancela disparos no AlarmManager do Android, que roda fora do app: um disparo acontece
 * mesmo com o app fechado, caído ou travado. São dois tipos de broadcast pro [AlarmeReceiver]:
 * - DISPARAR: a próxima ocorrência de cada alarme (e o botão de teste), um por id;
 * - RETOMAR: a rede de segurança do alarme em andamento ([Ajustes.emAndamento]). Enquanto ele
 *   toca, o [AlarmeService] empurra a retomada pra frente a cada poucos segundos; se o app
 *   morrer, ela dispara e o alarme volta sozinho. Só é cancelada quando a missão é cumprida.
 */
object Agendador {
    const val ACAO_DISPARAR = "com.implacavel.alarme.DISPARAR"
    const val ACAO_RETOMAR = "com.implacavel.alarme.RETOMAR"
    const val EXTRA_ID = "id"

    /** Enquanto o alarme toca, a retomada fica sempre este tanto à frente: se o app morrer, ele volta em até 10 s. */
    const val RETOMADA_MS = 10_000L

    /**
     * Segunda retomada, mais adiante: se o app travar (em vez de cair), a primeira chega com ele
     * travado e se perde quando o Android o encerra; esta chega com o app já reiniciado.
     */
    private const val RESERVA_MS = 90_000L

    /** Agenda a próxima ocorrência (ou a cancela, se o alarme estiver desligado). Retorna quando vai tocar. */
    fun agendar(ctx: Context, alarme: Alarme): ZonedDateTime? {
        if (!alarme.ativo) {
            cancelar(ctx, alarme.id)
            return null
        }
        val quando = alarme.proximoDisparo(ZonedDateTime.now())
        agendarEm(ctx, quando.toInstant().toEpochMilli(), disparo(ctx, alarme.id))
        return quando
    }

    /** Botão "Testar agora": toca o alarme de teste daqui a [segundos]. */
    fun agendarTeste(ctx: Context, segundos: Int) =
        agendarEm(ctx, System.currentTimeMillis() + segundos * 1_000L, disparo(ctx, Alarme.ID_TESTE))

    fun cancelar(ctx: Context, id: Int) = alarmManager(ctx).cancel(disparo(ctx, id))

    /** Arma (ou empurra pra frente) a retomada do alarme em andamento, daqui a [ms]. O Android arredonda pra no mínimo uns 5 s. */
    fun agendarRetomada(ctx: Context, ms: Long = RETOMADA_MS) {
        val agora = System.currentTimeMillis()
        agendarEm(ctx, agora + ms, retomada(ctx, reserva = false))
        agendarEm(ctx, agora + ms + RESERVA_MS, retomada(ctx, reserva = true))
    }

    /** Missão cumprida: não há mais o que retomar. */
    fun cancelarRetomada(ctx: Context) {
        alarmManager(ctx).cancel(retomada(ctx, reserva = false))
        alarmManager(ctx).cancel(retomada(ctx, reserva = true))
    }

    /**
     * Refaz os disparos a partir do que está salvo. Chamado pelo [BootReceiver] (reiniciar o celular
     * apaga tudo) e ao abrir o app (o "Forçar parada" também apaga). A retomada é refeita pelo [App].
     */
    fun reagendarTodos(ctx: Context) = Alarmes.lista.value.forEach { agendar(ctx, it) }

    private fun agendarEm(ctx: Context, quando: Long, pi: PendingIntent) {
        val am = alarmManager(ctx)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            // setAlarmClock: o agendamento mais forte do Android. Ignora a economia de bateria (Doze),
            // libera o início do serviço em segundo plano e mostra o ícone de despertador na barra.
            val abrirApp = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(quando, abrirApp), pi)
        } else {
            // Sem permissão de alarme exato (só acontece no Android 12): melhor atrasar do que não tocar
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
        }
    }

    /** Código id * 2, o mesmo das versões anteriores: atualizar o app substitui os agendamentos antigos em vez de duplicar. */
    private fun disparo(ctx: Context, id: Int): PendingIntent {
        val intent = Intent(ctx, AlarmeReceiver::class.java).setAction(ACAO_DISPARAR).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(ctx, id * 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun retomada(ctx: Context, reserva: Boolean): PendingIntent {
        val intent = Intent(ctx, AlarmeReceiver::class.java).setAction(ACAO_RETOMAR)
        return PendingIntent.getBroadcast(ctx, if (reserva) 1 else 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun alarmManager(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)
}
