package com.implacavel.alarme

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Um alarme salvo pelo usuário, com as regras de quando ele toca. Sem dependência do Android
 * (fora a serialização JSON), por isso as regras são testadas em AlarmeTest.
 *
 * @property dias dias em que repete, no padrão DayOfWeek.value (1 = segunda … 7 = domingo).
 *   Vazio = toca uma vez e se desliga sozinho.
 * @property missao só desliga dizendo STOP e olhando pra câmera de olhos abertos (veja Missao.kt).
 * @property volumeMaximo trava o volume de alarme no máximo enquanto toca (veja Sirene).
 * @property sonecaAte quando o disparo avulso pendente toca (epoch em ms): pausa automática ou
 *   retomada de um alarme interrompido (veja [Agendador.tocarDaqui]); null se não há nenhum.
 */
data class Alarme(
    val id: Int,
    val hora: Int,
    val minuto: Int,
    val rotulo: String = "",
    val dias: Set<Int> = emptySet(),
    val ativo: Boolean = true,
    val missao: Boolean = true,
    val volumeMaximo: Boolean = true,
    val sonecaAte: Long? = null,
) {
    val horario: String get() = hhmm(hora, minuto)

    /** Nome pra mostrar: o rótulo, ou "Alarme" se ele estiver em branco. */
    val nome: String get() = rotulo.ifBlank { "Alarme" }

    /** Próximo horário que bate com hora, minuto e dias, estritamente depois de [agora]. */
    fun proximoDisparo(agora: ZonedDateTime): ZonedDateTime {
        var alvo = agora.withHour(hora).withMinute(minuto).withSecond(0).withNano(0)
        if (!alvo.isAfter(agora)) alvo = alvo.plusDays(1)
        if (dias.isNotEmpty()) {
            while (alvo.dayOfWeek.value !in dias) alvo = alvo.plusDays(1)
        }
        return alvo
    }

    /** Horário da soneca, se ela ainda não passou. */
    fun sonecaPendente(agora: ZonedDateTime): ZonedDateTime? =
        sonecaAte?.let { Instant.ofEpochMilli(it).atZone(agora.zone) }?.takeIf { it.isAfter(agora) }

    /** Próxima vez que o alarme toca, contando a soneca; null se nada estiver agendado. */
    fun proximoToque(agora: ZonedDateTime): ZonedDateTime? {
        val normal = if (ativo) proximoDisparo(agora) else null
        return listOfNotNull(sonecaPendente(agora), normal).minByOrNull { it.toEpochSecond() }
    }

    fun paraJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("hora", hora)
        .put("minuto", minuto)
        .put("rotulo", rotulo)
        .put("dias", JSONArray(dias.sorted()))
        .put("ativo", ativo)
        .put("missao", missao)
        .put("volumeMaximo", volumeMaximo)
        .apply { if (sonecaAte != null) put("sonecaAte", sonecaAte) }

    companion object {
        /** Id reservado pro botão "Testar agora". Os alarmes salvos começam em 1. */
        const val ID_TESTE = 0

        fun teste() = Alarme(id = ID_TESTE, hora = 0, minuto = 0, rotulo = "Teste do Alarme Implacável", volumeMaximo = false)

        fun deJson(o: JSONObject): Alarme {
            val dias = o.optJSONArray("dias")
            return Alarme(
                id = o.getInt("id"),
                hora = o.getInt("hora"),
                minuto = o.getInt("minuto"),
                rotulo = o.optString("rotulo"),
                dias = if (dias == null) emptySet() else (0 until dias.length()).map { dias.getInt(it) }.toSet(),
                ativo = o.optBoolean("ativo", true),
                missao = o.optBoolean("missao", true),
                volumeMaximo = o.optBoolean("volumeMaximo", true),
                sonecaAte = if (o.has("sonecaAte")) o.getLong("sonecaAte") else null,
            )
        }
    }
}
