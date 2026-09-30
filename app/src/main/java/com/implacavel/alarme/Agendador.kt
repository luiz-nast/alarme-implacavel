package com.implacavel.alarme

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZonedDateTime

/**
 * Agenda e cancela disparos no AlarmManager do Android. Cada disparo é um broadcast para
 * [AlarmeReceiver] levando o id do alarme e se é uma soneca. Cada alarme tem duas vagas, e uma não
 * substitui a outra: o disparo normal (próxima ocorrência do horário) e a soneca (disparo avulso de
 * [tocarDaqui]: botão de teste, pausa automática e retomada de alarme interrompido).
 */
object Agendador {
    const val ACAO_DISPARAR = "com.implacavel.alarme.DISPARAR"
    const val EXTRA_ID = "id"
    const val EXTRA_SONECA = "soneca"

    /** Agenda a próxima ocorrência (ou a cancela, se o alarme estiver desligado). Retorna quando vai tocar. */
    fun agendar(ctx: Context, alarme: Alarme): ZonedDateTime? {
        if (!alarme.ativo) {
            // Só o disparo normal: a soneca continua valendo. Um alarme de uma vez só fica desligado ao
            // tocar; se o celular reiniciar no meio dele, a retomada fica na soneca e não pode ser perdida.
            alarmManager(ctx).cancel(disparo(ctx, alarme.id, soneca = false))
            return null
        }
        val quando = alarme.proximoDisparo(ZonedDateTime.now())
        agendarEm(ctx, alarme.id, quando.toInstant().toEpochMilli(), soneca = false)
        return quando
    }

    /**
     * Faz o alarme tocar daqui a [ms] (o Android arredonda pra no mínimo uns 5 s), na vaga da soneca.
     * Com [gravar], o horário fica em [Alarme.sonecaAte], pra aparecer na tela e sobreviver a um
     * reinício do celular: a pausa automática precisa disso; a retomada não, porque já sobrevive por
     * [Ajustes.alarmeEmAndamento], e não deve aparecer como soneca que dá pra cancelar.
     */
    fun tocarDaqui(ctx: Context, id: Int, ms: Long, gravar: Boolean = false) {
        val quando = System.currentTimeMillis() + ms
        agendarEm(ctx, id, quando, soneca = true)
        if (gravar) Alarmes.buscar(id)?.let { Alarmes.salvar(it.copy(sonecaAte = quando)) }
    }

    fun cancelarSoneca(ctx: Context, id: Int) {
        alarmManager(ctx).cancel(disparo(ctx, id, soneca = true))
        Alarmes.buscar(id)?.takeIf { it.sonecaAte != null }?.let { Alarmes.salvar(it.copy(sonecaAte = null)) }
    }

    /** Cancela o disparo normal e a soneca (alarme excluído). */
    fun cancelar(ctx: Context, id: Int) {
        alarmManager(ctx).cancel(disparo(ctx, id, soneca = false))
        alarmManager(ctx).cancel(disparo(ctx, id, soneca = true))
    }

    /**
     * Refaz todos os agendamentos a partir do que está salvo. Chamado pelo [BootReceiver] (reiniciar o
     * celular apaga tudo) e ao abrir o app (o "Forçar parada" também apaga).
     */
    fun reagendarTodos(ctx: Context) {
        val agora = System.currentTimeMillis()
        Alarmes.lista.value.forEach { alarme ->
            agendar(ctx, alarme)
            val ate = alarme.sonecaAte ?: return@forEach
            if (ate > agora) {
                agendarEm(ctx, alarme.id, ate, soneca = true)
            } else {
                Alarmes.salvar(alarme.copy(sonecaAte = null)) // venceu com o celular desligado
            }
        }
    }

    private fun agendarEm(ctx: Context, id: Int, quando: Long, soneca: Boolean) {
        val am = alarmManager(ctx)
        val pi = disparo(ctx, id, soneca)
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

    /** Um PendingIntent por vaga: códigos distintos (id * 2 e id * 2 + 1) pra soneca não substituir o disparo normal. */
    private fun disparo(ctx: Context, id: Int, soneca: Boolean): PendingIntent {
        val intent = Intent(ctx, AlarmeReceiver::class.java)
            .setAction(ACAO_DISPARAR)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_SONECA, soneca)
        val codigo = id * 2 + if (soneca) 1 else 0
        return PendingIntent.getBroadcast(ctx, codigo, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun alarmManager(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)
}
