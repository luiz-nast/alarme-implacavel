package com.implacavel.alarme

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** Volume forte: 50% do volume de alarme do celular. */
const val VOLUME_FORTE = 0.5f

/** O botão "Testar agora" toca a 35% do volume de alarme. */
const val VOLUME_TESTE = 0.35f

/**
 * Um alarme salvo pelo usuário, com as regras de quando ele toca (e, no fim do arquivo, o
 * [EmAndamento], o alarme que tocou e ainda não foi cumprido). Sem dependência do Android (fora a
 * serialização JSON), por isso as regras são testadas em AlarmeTest.
 *
 * @property dias dias em que repete, no padrão DayOfWeek.value (1 = segunda … 7 = domingo).
 *   Vazio = toca uma vez e se desliga sozinho.
 * @property missao só desliga dizendo STOP e olhando pra câmera de olhos abertos (veja Missao.kt).
 * @property volumeForte trava o volume de alarme em [VOLUME_FORTE] enquanto toca; sem isso, trava no
 *   volume em que ele estava (veja [EmAndamento.volumeTravado]). Nos dois casos, não dá pra abaixar.
 */
data class Alarme(
    val id: Int,
    val hora: Int,
    val minuto: Int,
    val rotulo: String = "",
    val dias: Set<Int> = emptySet(),
    val ativo: Boolean = true,
    val missao: Boolean = true,
    val volumeForte: Boolean = true,
) {
    val horario: String get() = hhmm(hora, minuto)

    /** Nome pra mostrar: o rótulo, ou "Alarme" se ele estiver em branco. */
    val nome: String get() = rotulo.ifBlank { "Alarme" }

    /** O alarme do botão "Testar agora" ([ID_TESTE]): toca mais baixo e pede menos da câmera. */
    val deTeste: Boolean get() = id == ID_TESTE

    /** Próximo horário que bate com hora, minuto e dias, estritamente depois de [agora]. */
    fun proximoDisparo(agora: ZonedDateTime): ZonedDateTime {
        var alvo = agora.withHour(hora).withMinute(minuto).withSecond(0).withNano(0)
        if (!alvo.isAfter(agora)) alvo = alvo.plusDays(1)
        if (dias.isNotEmpty()) {
            while (alvo.dayOfWeek.value !in dias) alvo = alvo.plusDays(1)
        }
        return alvo
    }

    fun paraJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("hora", hora)
        .put("minuto", minuto)
        .put("rotulo", rotulo)
        .put("dias", JSONArray(dias.sorted()))
        .put("ativo", ativo)
        .put("missao", missao)
        .put("volumeForte", volumeForte)

    companion object {
        /** Id reservado pro botão "Testar agora". Os alarmes salvos começam em 1. */
        const val ID_TESTE = 0

        fun teste() = Alarme(id = ID_TESTE, hora = 0, minuto = 0, rotulo = "Teste do Alarme Implacável", volumeForte = false)

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
                // "volumeMaximo" é o nome das versões anteriores
                volumeForte = o.optBoolean("volumeForte", o.optBoolean("volumeMaximo", true)),
            )
        }
    }
}

/**
 * O alarme que tocou e ainda não foi cumprido, com tudo pra ele voltar igual se o app morrer: o
 * [alarme] como estava, quando tocou ([desde], epoch em ms), a [musica] (URI) e o [volume] de
 * alarme de antes (a Sirene o devolve no fim; veja [volumeTravado]).
 * Editar o alarme, trocar a música ou abaixar o volume depois não muda nada.
 */
data class EmAndamento(val alarme: Alarme, val desde: Long, val musica: String?, val volume: Int) {
    /**
     * Outro alarme disparou no meio, [agora] (epoch em ms). Teste no meio de um alarme não muda nada.
     * Alarme de verdade no meio do teste toma o lugar dele, com as regras dele (a câmera do teste pede
     * bem menos, veja [metaOlhar]), tocando desde agora e com o volume de antes do teste. Dois alarmes
     * de verdade viram um: este continua, com a exigência maior dos dois.
     */
    fun juntar(outro: Alarme, agora: Long): EmAndamento = when {
        outro.deTeste -> this
        alarme.deTeste -> copy(alarme = outro, desde = agora)
        else -> copy(alarme = alarme.copy(missao = alarme.missao || outro.missao, volumeForte = alarme.volumeForte || outro.volumeForte))
    }

    /** Volume de alarme travado enquanto toca, numa escala até [maximo]: 35% no teste, 50% no volume forte, senão o de antes. */
    fun volumeTravado(maximo: Int): Int = when {
        alarme.deTeste -> (maximo * VOLUME_TESTE).roundToInt()
        alarme.volumeForte -> (maximo * VOLUME_FORTE).roundToInt()
        else -> volume
    }

    fun paraJson(): JSONObject = JSONObject()
        .put("alarme", alarme.paraJson())
        .put("desde", desde)
        .put("volume", volume)
        .apply { if (musica != null) put("musica", musica) }

    companion object {
        fun deJson(o: JSONObject) = EmAndamento(
            alarme = Alarme.deJson(o.getJSONObject("alarme")),
            desde = o.getLong("desde"),
            musica = if (o.has("musica")) o.getString("musica") else null,
            volume = o.getInt("volume"),
        )
    }
}
