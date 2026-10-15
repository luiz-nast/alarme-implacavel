package com.implacavel.alarme

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** Volume do alarme tocando: começa em [inicio] e sobe em linha reta até [fim] em [ms] (frações do volume de alarme do celular). */
class Subida(val inicio: Float, val fim: Float, val ms: Long)

/**
 * Alarme e teste: de 10% a 40% em 30 s, exato (mesmo com o celular mais alto). É música, pra ouvir um
 * pouco antes de acordar, e o teste é feito em casa, com a família por perto. O dono pediu; não há
 * mais opção de volume forte.
 */
val SUBIDA_ALARME = Subida(0.10f, 0.40f, 30_000)

/** Remédio: um toque suave, sem a música, de 15% a 75% em 2 min. */
val SUBIDA_REMEDIO = Subida(0.15f, 0.75f, 120_000)

/** A voz do app (bom dia, avisos, frases, a IA, parabéns), sempre igual do começo ao fim da fala. O dono pediu 45% (30% ficou baixo). */
const val VOLUME_DA_VOZ = 0.45f

/**
 * Um alarme salvo pelo usuário, com as regras de quando ele toca (e, no fim do arquivo, o
 * [EmAndamento], o alarme que tocou e ainda não foi cumprido). Sem dependência do Android (fora a
 * serialização JSON), por isso as regras são testadas em AlarmeTest.
 *
 * @property dias dias em que repete, no padrão DayOfWeek.value (1 = segunda … 7 = domingo).
 *   Vazio = toca uma vez e se desliga sozinho.
 * @property missao só desliga dizendo STOP e olhando pra câmera de olhos abertos (veja Missao.kt).
 * @property remedio lembrete de remédio: toca um toque suave do app em vez da música, subindo como
 *   [SUBIDA_REMEDIO].
 * @property caminhada despertador "conversa + caminhada": depois do STOP, 10 min de conversa com a IA do
 *   celular, 4 min pra sair e 16 min andando na rua, chova ou faça sol (sem a IA, direto pra caminhada). Sem ele, o
 *   despertador é o "câmera + frases". Só vale com [missao].
 */
data class Alarme(
    val id: Int,
    val hora: Int,
    val minuto: Int,
    val rotulo: String = "",
    val dias: Set<Int> = emptySet(),
    val ativo: Boolean = true,
    val missao: Boolean = true,
    val remedio: Boolean = false,
    val caminhada: Boolean = false,
) {
    val horario: String get() = hhmm(hora, minuto)

    /** Nome pra mostrar: o rótulo, ou "Alarme" se ele estiver em branco. */
    val nome: String get() = rotulo.ifBlank { "Alarme" }

    /** Um dos alarmes dos botões de teste ([ID_TESTE] e [ID_TESTE_CAMINHADA]): cada etapa pede bem menos tempo (veja [metaOlhar]). */
    val deTeste: Boolean get() = id == ID_TESTE || id == ID_TESTE_CAMINHADA

    /**
     * Um tipo só por alarme: com missão é despertador ("câmera + frases" ou "conversa + caminhada"), sem
     * missão é lembrete de remédio. Versões antigas deixavam missão e remédio juntos, ou nenhum dos dois.
     */
    fun normalizado(): Alarme = copy(remedio = !missao, caminhada = caminhada && missao)

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
        .put("remedio", remedio)
        .put("caminhada", caminhada)

    companion object {
        /** Id reservado pro botão "Testar câmera + frases". Os alarmes salvos começam em 1. */
        const val ID_TESTE = 0

        /** Id reservado pro botão "Testar conversa + caminhada". */
        const val ID_TESTE_CAMINHADA = -1

        /** O alarme de um botão de teste: "câmera + frases", ou com [caminhada], "conversa + caminhada". */
        fun teste(caminhada: Boolean = false) =
            if (caminhada) {
                Alarme(id = ID_TESTE_CAMINHADA, hora = 0, minuto = 0, rotulo = "Teste conversa + caminhada", caminhada = true)
            } else {
                Alarme(id = ID_TESTE, hora = 0, minuto = 0, rotulo = "Teste câmera + frases")
            }

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
                remedio = o.optBoolean("remedio", false),
                caminhada = o.optBoolean("caminhada", false),
                // "volumeForte" (e "volumeMaximo"), das versões anteriores, não existe mais: ignorado
            )
        }
    }
}

/**
 * O alarme que tocou e ainda não foi cumprido, com tudo pra ele voltar igual se o app morrer: o
 * [alarme] como estava, quando tocou ([desde], epoch em ms), a [musica] (URI) e o [volume] de
 * alarme de antes (a Sirene o devolve no fim; veja [volumeAgora]).
 * Editar o alarme, trocar a música ou abaixar o volume depois não muda nada.
 */
data class EmAndamento(val alarme: Alarme, val desde: Long, val musica: String?, val volume: Int) {
    /**
     * Outro alarme disparou no meio, [agora] (epoch em ms). Teste no meio de um alarme não muda nada.
     * Alarme de verdade no meio do teste toma o lugar dele, com as regras dele (a câmera do teste pede
     * bem menos, veja [metaOlhar]), tocando desde agora e com o volume de antes do teste. Dois alarmes
     * de verdade viram um: este continua, com a exigência maior dos dois (e com a música, se um deles tiver;
     * e com a conversa + caminhada, se um deles tiver).
     */
    fun juntar(outro: Alarme, agora: Long): EmAndamento = when {
        outro.deTeste -> this
        alarme.deTeste -> copy(alarme = outro, desde = agora)
        else -> copy(
            alarme = alarme.copy(
                missao = alarme.missao || outro.missao,
                remedio = alarme.remedio && outro.remedio,
                caminhada = alarme.caminhada || outro.caminhada,
            ),
        )
    }

    /**
     * Volume de alarme em [agoraMs] (epoch), numa escala até [maximo], pela [Subida] do alarme
     * ([SUBIDA_ALARME] ou [SUBIDA_REMEDIO]) desde que tocou ([desde]); no fim dela, fica lá. Depois de o app
     * cair, continua de onde estava. Relógio que voltou (antes de [desde]) conta como subida feita: o
     * volume só sobe. A Sirene põe o volume nele no começo e não deixa abaixar dele (subir pode).
     */
    fun volumeAgora(maximo: Int, agoraMs: Long): Int {
        val s = subida
        val passou = agoraMs - desde
        val subiu = if (passou < 0) 1f else (passou.toFloat() / s.ms).coerceAtMost(1f)
        return (maximo * (s.inicio + (s.fim - s.inicio) * subiu)).roundToInt()
    }

    /** Volume dos sons de aviso (bipe positivo, plim, parabéns): o do fim da subida (40%), sempre, mesmo que a subida não tenha acabado. */
    fun volumeDosAvisos(maximo: Int): Int = (maximo * subida.fim).roundToInt()

    /** Volume da voz do app: [VOLUME_DA_VOZ], abaixo dos avisos. */
    fun volumeDaVoz(maximo: Int): Int = (maximo * VOLUME_DA_VOZ).roundToInt()

    private val subida get() = if (alarme.remedio) SUBIDA_REMEDIO else SUBIDA_ALARME

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
