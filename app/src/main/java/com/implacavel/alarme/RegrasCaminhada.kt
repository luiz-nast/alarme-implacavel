// Regras da caminhada da manhã (despertador "conversa + caminhada"): só conta o tempo andando a pé, na rua,
// com GPS de verdade. Chova ou faça sol, com ou sem internet (o GPS ouve os satélites direto), não tem plano B:
// parado, os bipes não param. Funções puras, testadas em CaminhadaTest.
package com.implacavel.alarme

import java.util.Locale
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Tempo andando pra desligar o alarme com caminhada: parado, o relógio não anda. */
const val CAMINHADA_MS = 16 * 60_000L

/** No botão "Testar conversa + caminhada", só isto. */
const val CAMINHADA_TESTE_MS = 60_000L

/** Depois da conversa, o prazo pra se vestir e sair de casa: andar nele já conta, e parado não bipa. */
const val PRAZO_SAIR_MS = 4 * 60_000L

const val PRAZO_SAIR_TESTE_MS = 20_000L

/**
 * Depois do prazo pra sair, parado (sem sinal de "andando") por esse tempo: um bipe ([primeiroBipeParado]);
 * depois, um a cada [ENTRE_BIPES_PARADO_MS], cada um mais alto até o máximo, até voltar a andar. Não zera nada.
 */
const val PARADO_MS = 10_000L

const val ENTRE_BIPES_PARADO_MS = 3_000L

/** Fixo com incerteza maior que isso (dentro de casa, entre prédios) não conta. */
const val PRECISAO_MAX_M = 25f

/** Velocidade medida pelo GPS com incerteza maior que isso não vale: a velocidade sai do deslocamento. */
private const val PRECISAO_VELOCIDADE_MAX = 1.5f

/** Andando ou correndo a pé: de 0,5 m/s (passo lento) a 4 m/s (corrida leve). Mais rápido é carro, ônibus ou bicicleta. */
private const val VELOCIDADE_MIN = 0.5f
private const val VELOCIDADE_MAX = 4.0f

/** O máximo que um fixo soma: um buraco no sinal não vira minutos andados. */
private const val PASSO_MAX_MS = 3_000L

private const val RAIO_DA_TERRA_M = 6_371_000.0

/** Quanto tempo andando a caminhada do [alarme] pede. */
fun metaCaminhada(alarme: Alarme): Long = if (alarme.deTeste) CAMINHADA_TESTE_MS else CAMINHADA_MS

/** O prazo pra sair de casa do [alarme], depois da conversa. */
fun prazoSair(alarme: Alarme): Long = if (alarme.deTeste) PRAZO_SAIR_TESTE_MS else PRAZO_SAIR_MS

/**
 * Um fixo do GPS, sem o Location do Android (pra testar): [tempoMs] no relógio que não pula
 * (SystemClock.elapsedRealtime), [precisaoM] o raio de incerteza em metros (null = o GPS não disse),
 * [velocidadeMs] a velocidade medida pelo GPS (null = não mediu), [precisaoVelocidade] a incerteza
 * dela (null = não disse) e se veio de um app de localização [falso].
 */
data class Fixo(
    val tempoMs: Long,
    val latitude: Double,
    val longitude: Double,
    val precisaoM: Float?,
    val velocidadeMs: Float? = null,
    val precisaoVelocidade: Float? = null,
    val falso: Boolean = false,
)

/** O que um fixo do GPS diz da caminhada. */
enum class Movimento { FALSO, IMPRECISO, PARADO, ANDANDO, RAPIDO_DEMAIS }

/** O fixo vale pra conta: não é localização falsa e tem precisão de rua ([PRECISAO_MAX_M]). */
fun fixoValido(fixo: Fixo): Boolean = !fixo.falso && fixo.precisaoM != null && fixo.precisaoM <= PRECISAO_MAX_M

/** Distância em metros entre dois fixos, pela fórmula de haversine (a Terra como uma esfera: de sobra pra passos). */
fun distanciaM(a: Fixo, b: Fixo): Double {
    val latA = Math.toRadians(a.latitude)
    val latB = Math.toRadians(b.latitude)
    val meioLat = sin((latB - latA) / 2)
    val meioLon = sin(Math.toRadians(b.longitude - a.longitude) / 2)
    val h = meioLat * meioLat + cos(latA) * cos(latB) * meioLon * meioLon
    return 2 * RAIO_DA_TERRA_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/**
 * Velocidade no [atual], em m/s: a medida pelo GPS, se a incerteza dela é de até 1,5 m/s (ou ele não
 * disse a incerteza); senão, o deslocamento desde o [anterior] (o último fixo válido) dividido pelo
 * tempo. Null sem como saber (sem velocidade boa e sem anterior).
 */
fun velocidade(anterior: Fixo?, atual: Fixo): Float? {
    val medida = atual.velocidadeMs
    if (medida != null && (atual.precisaoVelocidade == null || atual.precisaoVelocidade <= PRECISAO_VELOCIDADE_MAX)) return medida
    if (anterior == null) return null
    val segundos = (atual.tempoMs - anterior.tempoMs) / 1000.0
    return if (segundos > 0) (distanciaM(anterior, atual) / segundos).toFloat() else null
}

/** O [atual] lido pra caminhada, com o [anterior] (o último fixo válido; null se nenhum ainda). */
fun movimento(anterior: Fixo?, atual: Fixo): Movimento {
    if (atual.falso) return Movimento.FALSO
    if (!fixoValido(atual)) return Movimento.IMPRECISO
    val v = velocidade(anterior, atual) ?: return Movimento.PARADO
    return when {
        v > VELOCIDADE_MAX -> Movimento.RAPIDO_DEMAIS
        v >= VELOCIDADE_MIN -> Movimento.ANDANDO
        else -> Movimento.PARADO
    }
}

/**
 * Quanto o [atual] soma na caminhada, em ms: com a pessoa andando a pé ([movimento]), o tempo desde o
 * [anterior] (o último fixo válido), até 3 s; senão, 0. No primeiro fixo válido (sem [anterior]), 0.
 * Quem chama guarda como [anterior] só os fixos que passam em [fixoValido].
 */
fun passoDeCaminhada(anterior: Fixo?, atual: Fixo): Long =
    if (anterior == null || movimento(anterior, atual) != Movimento.ANDANDO) 0 else (atual.tempoMs - anterior.tempoMs).coerceIn(0, PASSO_MAX_MS)

/**
 * Quando (SystemClock.elapsedRealtime) toca o 1º bipe de parado: [PARADO_MS] depois do último sinal de
 * "andando" ([ultimoPasso]) ou do fim do prazo pra sair ([prazoAte]), o que vier depois. Os seguintes, a
 * cada [ENTRE_BIPES_PARADO_MS]. A vigia do serviço e a contagem na tela ([segundosProBipe]) contam daqui.
 */
fun primeiroBipeParado(ultimoPasso: Long, prazoAte: Long): Long = maxOf(ultimoPasso, prazoAte) + PARADO_MS

/** Contagem na tela, parado depois do prazo pra sair: segundos até o 1º bipe (0 = já bipando). Null ainda no prazo. */
fun segundosProBipe(agora: Long, ultimoPasso: Long, prazoAte: Long): Int? =
    if (agora < prazoAte) null else ceil((primeiroBipeParado(ultimoPasso, prazoAte) - agora) / 1000.0).toInt().coerceAtLeast(0)

/** Aviso na tela durante o prazo pra sair: "Você tem 6:32 pra começar a andar". Null depois dele. */
fun avisoDoPrazo(agora: Long, prazoAte: Long): String? =
    if (agora < prazoAte) "Você tem ${tempoRestante(prazoAte - agora)} pra começar a andar" else null

/** Tempo já andado, sem arredondar pra cima (é o que já foi): "7:12", "0:28". */
fun tempoAndado(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

/** Velocidade na tela, com vírgula: "1,3 m/s". */
fun textoDaVelocidade(metrosPorSegundo: Float): String = String.format(Locale.ROOT, "%.1f m/s", metrosPorSegundo).replace('.', ',')
