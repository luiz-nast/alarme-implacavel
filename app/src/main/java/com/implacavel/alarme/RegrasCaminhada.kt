// Regras da caminhada da manhã (despertador "conversa + caminhada"): só conta o tempo andando a pé, na rua,
// com GPS de verdade. Chova ou faça sol, com ou sem internet (o GPS ouve os satélites direto), não tem plano B:
// parado, os bipes não param. Funções puras, testadas em CaminhadaTest.
package com.implacavel.alarme

import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
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

/** Fixo com incerteza maior que isso (dentro de casa, entre prédios) não conta. Era 25 m: na cama, perto da janela, passava. */
const val PRECISAO_MAX_M = 20f

/** Velocidade medida pelo GPS com incerteza maior que isso não vale (nem pra dizer que está parado). */
private const val PRECISAO_VELOCIDADE_MAX = 1.5f

/** Andando ou correndo a pé: de 0,5 m/s (passo lento) a 4 m/s (corrida leve). Mais rápido é carro, ônibus ou bicicleta. */
private const val VELOCIDADE_MIN = 0.5f
private const val VELOCIDADE_MAX = 4.0f

/**
 * Andar é sair do lugar de um jeito que o GPS parado não imita. O deslocamento é medido entre o fixo de agora e o
 * mais velho dos últimos [JANELA_MS] ([janelaDepois]), com pelo menos [JANELA_MIN_MS] entre os dois (antes disso,
 * ainda não dá pra saber). A janela longa é o que separa os dois: andando, o deslocamento cresce com o tempo;
 * parado, o ponto do GPS só treme em volta do lugar. Os números saíram de uma simulação de GPS com erro de 5 a
 * 20 m (veja o README e `naSimulacaoAndarContaEDeitadoNao`).
 */
const val JANELA_MS = 30_000L
private const val JANELA_MIN_MS = 10_000L

/** Na janela, o deslocamento tem que passar disto e de 80% da incerteza média dos dois fixos. */
private const val DESLOCAMENTO_MIN_M = 10.0
private const val FATOR_DA_INCERTEZA = 0.8

/**
 * Andando, as duas metades da janela (do começo ao fixo do meio e do meio até agora) avançam cada uma pelo menos
 * 0,25 m/s, e sem voltar pra trás (até 120° entre elas: dobrar uma esquina vale); o ponto parado vai e volta.
 */
private const val AVANCO_DA_METADE = 0.25
private const val ANGULO_ENTRE_METADES_MAX = 120.0

/**
 * Dos fixos da janela em que o GPS mediu velocidade, 60% têm que dar velocidade de andar ([VELOCIDADE_MIN]), mesmo
 * medida com incerteza: parado, o GPS mede perto de zero (o ponto escorregando sozinho dentro de casa não é andar).
 */
private const val COM_VELOCIDADE_DE_ANDAR_MIN = 0.6

/**
 * Dos fixos da janela com velocidade de andar e rumo medido (pra onde o celular vai), 60% têm o rumo a até 60° da
 * direção do deslocamento. Andando, o rumo segue o caminho; o braço balançando dá rumo pra todo lado. Vale com pelo
 * menos [RUMOS_MIN] rumos e com rumo em pelo menos metade desses fixos.
 */
private const val RUMO_TOLERANCIA_GRAUS = 60.0
private const val RUMO_CONCORDA_MIN = 0.6
private const val RUMOS_MIN = 5

/**
 * Sem o rumo (o GPS não mediu, ou mediu pouco), a regra fica mais dura: 20 m e 150% da incerteza, pelo menos 20 s de
 * janela, e os três terços dela avançando cada um 0,35 m/s, sem virar mais de 75° de um pro outro.
 */
private const val SEM_RUMO_DESLOCAMENTO_MIN_M = 20.0
private const val SEM_RUMO_FATOR_DA_INCERTEZA = 1.5
private const val SEM_RUMO_JANELA_MIN_MS = 20_000L
private const val SEM_RUMO_AVANCO_DO_TERCO = 0.35
private const val SEM_RUMO_ANGULO_MAX = 75.0

/**
 * Pulo do ponto: de um fixo pro seguinte, mais do que andar a 4 m/s mais isto. É o GPS errando (dentro de casa,
 * perto da janela), não a pessoa ([janelaDepois]).
 */
private const val PULO_FOLGA_M = 10.0

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
 * dela (null = não disse), se veio de um app de localização [falso] e o [rumo] medido (pra onde o celular vai, em
 * graus do norte, no sentido do relógio; null = não mediu).
 */
data class Fixo(
    val tempoMs: Long,
    val latitude: Double,
    val longitude: Double,
    val precisaoM: Float?,
    val velocidadeMs: Float? = null,
    val precisaoVelocidade: Float? = null,
    val falso: Boolean = false,
    val rumo: Float? = null,
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
 * Onde [fixo] fica, em metros, a leste e ao norte da [origem] (o chão como um plano: de sobra pra uma caminhada).
 * É o que a tela usa pra desenhar a trilha.
 */
fun emMetros(origem: Fixo, fixo: Fixo): Pair<Double, Double> {
    val leste = Math.toRadians(fixo.longitude - origem.longitude) * RAIO_DA_TERRA_M * cos(Math.toRadians(origem.latitude))
    val norte = Math.toRadians(fixo.latitude - origem.latitude) * RAIO_DA_TERRA_M
    return leste to norte
}

/** De [ultimo] pro [atual], o ponto andou mais do que dá pra andar a pé: o GPS pulou ([PULO_FOLGA_M]). */
private fun pulou(ultimo: Fixo, atual: Fixo): Boolean {
    val segundos = (atual.tempoMs - ultimo.tempoMs) / 1000.0
    return segundos > 0 && distanciaM(ultimo, atual) > VELOCIDADE_MAX * segundos + PULO_FOLGA_M
}

/** A velocidade medida pelo GPS, se a incerteza dela é aceitável (ou ele não disse); senão null. */
private fun medidaBoa(fixo: Fixo): Float? =
    fixo.velocidadeMs?.takeIf { fixo.precisaoVelocidade == null || fixo.precisaoVelocidade <= PRECISAO_VELOCIDADE_MAX }

/**
 * A janela da caminhada: os [fixos] válidos dos últimos [JANELA_MS], do mais velho ao mais novo, e um [pulo] do
 * ponto ainda sem confirmar (o fixo que pulou longe do último: se o seguinte voltar pro caminho, ele some).
 */
data class Janela(val fixos: List<Fixo> = emptyList(), val pulo: Fixo? = null)

/**
 * A [janela] depois do [atual] (ele entra se for válido): é a que [avaliar] e [passoDeCaminhada] recebem no
 * próximo fixo. Um pulo fica de lado ([Janela.pulo]): se o seguinte volta pro caminho, foi um erro de um segundo e
 * nada se perde; se o seguinte confirma o lugar novo, a janela recomeça dos dois. Parado pelo GPS (duas medidas boas
 * seguidas abaixo de 0,5 m/s), a janela recomeça dali: depois de um farol, a volta conta como um começo.
 */
fun janelaDepois(janela: Janela, atual: Fixo): Janela {
    if (!fixoValido(atual)) return janela
    val ultimo = janela.fixos.lastOrNull()
    if (ultimo != null && pulou(ultimo, atual)) {
        val pulo = janela.pulo
        return if (pulo != null && !pulou(pulo, atual)) Janela(listOf(pulo, atual)) else janela.copy(pulo = atual)
    }
    val parouAgora = medidaBoa(atual)?.let { it < VELOCIDADE_MIN } == true
    val parouAntes = ultimo?.let(::medidaBoa)?.let { it < VELOCIDADE_MIN } == true
    if (parouAgora && parouAntes && ultimo != null) return Janela(listOf(ultimo, atual))
    return Janela((janela.fixos + atual).filter { atual.tempoMs - it.tempoMs in 0..JANELA_MS })
}

/** O fixo de onde o deslocamento é medido: o mais velho da [janela] de pelo menos [JANELA_MIN_MS] antes do [atual]. Null sem nenhum. */
private fun inicioDaJanela(janela: Janela, atual: Fixo): Fixo? =
    janela.fixos.firstOrNull { atual.tempoMs - it.tempoMs in JANELA_MIN_MS..JANELA_MS }

/**
 * Velocidade de verdade no [atual], em m/s: o deslocamento em linha reta desde o começo da [janela] dividido pelo
 * tempo. Null sem janela ainda (os primeiros segundos). A velocidade que o GPS mede sozinha não serve pra isso: ela
 * é a do celular, e o celular balançando na mão, com a pessoa parada na cama, passa de 1 m/s.
 */
fun velocidade(janela: Janela, atual: Fixo): Float? {
    val inicio = inicioDaJanela(janela, atual) ?: return null
    return (distanciaM(inicio, atual) / ((atual.tempoMs - inicio.tempoMs) / 1000.0)).toFloat()
}

/** O que um fixo diz da caminhada ([movimento]) e por quê ([motivo], curto, pro log da caminhada: "pulo", "curto"…). */
data class Avaliacao(val movimento: Movimento, val motivo: String)

/**
 * O [atual] lido pra caminhada, com a [janela] (de [janelaDepois]). Andando = saiu do lugar de verdade: na janela,
 * a velocidade em linha reta fica entre 0,5 e 4 m/s e o deslocamento passa de [DESLOCAMENTO_MIN_M] e da incerteza
 * ([FATOR_DA_INCERTEZA]); a velocidade medida pelo GPS não diz que está parado (nem agora nem na maior parte da
 * janela); as duas metades da janela avançam no mesmo sentido; e o rumo medido segue o caminho ou, sem rumo, a regra
 * mais dura de [SEM_RUMO_DESLOCAMENTO_MIN_M]. Um pulo do ponto não conta. Mais rápido que 4 m/s: carro ou bicicleta.
 */
fun avaliar(janela: Janela, atual: Fixo): Avaliacao {
    if (atual.falso) return Avaliacao(Movimento.FALSO, "falso")
    if (!fixoValido(atual)) return Avaliacao(Movimento.IMPRECISO, "impreciso")
    val medida = medidaBoa(atual)
    if (medida != null && medida > VELOCIDADE_MAX) return Avaliacao(Movimento.RAPIDO_DEMAIS, "rápido medido")
    janela.fixos.lastOrNull()?.let { if (pulou(it, atual)) return Avaliacao(Movimento.PARADO, "pulo") }
    val inicio = inicioDaJanela(janela, atual) ?: return Avaliacao(Movimento.PARADO, "sem janela")
    val segundos = (atual.tempoMs - inicio.tempoMs) / 1000.0
    val (leste, norte) = emMetros(inicio, atual)
    val deslocamento = hypot(leste, norte)
    val real = deslocamento / segundos
    val incertezaMedia = ((inicio.precisaoM ?: PRECISAO_MAX_M) + (atual.precisaoM ?: PRECISAO_MAX_M)) / 2.0
    if (real > VELOCIDADE_MAX) return Avaliacao(Movimento.RAPIDO_DEMAIS, "rápido")
    if (real < VELOCIDADE_MIN) return Avaliacao(Movimento.PARADO, "lento")
    if (deslocamento < maxOf(DESLOCAMENTO_MIN_M, FATOR_DA_INCERTEZA * incertezaMedia)) return Avaliacao(Movimento.PARADO, "curto")
    if (medida != null && medida < VELOCIDADE_MIN) return Avaliacao(Movimento.PARADO, "parado pelo GPS")
    val trecho = janela.fixos.filter { it.tempoMs >= inicio.tempoMs } + atual
    val comVelocidade = trecho.filter { it.velocidadeMs != null }
    val movendo = comVelocidade.filter { it.velocidadeMs!! >= VELOCIDADE_MIN }
    if (comVelocidade.size >= RUMOS_MIN && movendo.size < COM_VELOCIDADE_DE_ANDAR_MIN * comVelocidade.size) {
        return Avaliacao(Movimento.PARADO, "parado pelo GPS")
    }
    // As duas metades: do começo ao fixo mais perto do meio do tempo, e dele até agora
    val meio = maisPerto(trecho, (inicio.tempoMs + atual.tempoMs) / 2)
    if (!avanca(inicio, meio, AVANCO_DA_METADE) || !avanca(meio, atual, AVANCO_DA_METADE)) return Avaliacao(Movimento.PARADO, "metade parada")
    if (anguloEntre(emMetros(inicio, meio), emMetros(meio, atual)) > ANGULO_ENTRE_METADES_MAX) return Avaliacao(Movimento.PARADO, "vai e volta")
    // O rumo medido nos fixos em que o GPS viu velocidade de andar
    val comRumo = movendo.filter { it.rumo != null }
    if (movendo.size >= RUMOS_MIN && comRumo.size >= maxOf(RUMOS_MIN, (movendo.size + 1) / 2)) {
        val direcao = Math.toDegrees(atan2(leste, norte))
        val concordam = comRumo.count { diferencaDeRumo(it.rumo!!.toDouble(), direcao) <= RUMO_TOLERANCIA_GRAUS }
        return if (concordam < RUMO_CONCORDA_MIN * comRumo.size) Avaliacao(Movimento.PARADO, "rumo") else Avaliacao(Movimento.ANDANDO, "andando")
    }
    // Sem o rumo: mais deslocamento, mais janela e os três terços avançando no mesmo sentido
    if (deslocamento < maxOf(SEM_RUMO_DESLOCAMENTO_MIN_M, SEM_RUMO_FATOR_DA_INCERTEZA * incertezaMedia)) return Avaliacao(Movimento.PARADO, "curto sem rumo")
    if (atual.tempoMs - inicio.tempoMs < SEM_RUMO_JANELA_MIN_MS) return Avaliacao(Movimento.PARADO, "curto sem rumo")
    val terco1 = maisPerto(trecho, inicio.tempoMs + (atual.tempoMs - inicio.tempoMs) / 3)
    val terco2 = maisPerto(trecho, inicio.tempoMs + 2 * (atual.tempoMs - inicio.tempoMs) / 3)
    val tercos = listOf(inicio, terco1, terco2, atual)
    if (tercos.zipWithNext().any { (a, b) -> !avanca(a, b, SEM_RUMO_AVANCO_DO_TERCO) }) return Avaliacao(Movimento.PARADO, "terço parado")
    val passos = tercos.zipWithNext { a, b -> emMetros(a, b) }
    if (passos.zipWithNext().any { (a, b) -> anguloEntre(a, b) > SEM_RUMO_ANGULO_MAX }) return Avaliacao(Movimento.PARADO, "terço virou")
    return Avaliacao(Movimento.ANDANDO, "andando sem rumo")
}

/** O fixo do [trecho] mais perto do instante [tempoMs]. */
private fun maisPerto(trecho: List<Fixo>, tempoMs: Long): Fixo = trecho.minBy { abs(it.tempoMs - tempoMs) }

/** De [a] até [b] (que vem depois), o ponto avançou pelo menos [mPorS] metros por segundo. */
private fun avanca(a: Fixo, b: Fixo, mPorS: Double): Boolean {
    val segundos = (b.tempoMs - a.tempoMs) / 1000.0
    return segundos > 0 && distanciaM(a, b) >= mPorS * segundos
}

/** Só o movimento de [avaliar]. */
fun movimento(janela: Janela, atual: Fixo): Movimento = avaliar(janela, atual).movimento

/** Ângulo entre dois deslocamentos (leste, norte), de 0 a 180°; com um deles nulo, 180 (não avança). */
private fun anguloEntre(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val na = hypot(a.first, a.second)
    val nb = hypot(b.first, b.second)
    if (na == 0.0 || nb == 0.0) return 180.0
    return Math.toDegrees(acos(((a.first * b.first + a.second * b.second) / (na * nb)).coerceIn(-1.0, 1.0)))
}

/** Diferença entre dois rumos em graus, de 0 a 180. */
private fun diferencaDeRumo(a: Double, b: Double): Double {
    val d = abs(a - b) % 360
    return if (d > 180) 360 - d else d
}

/**
 * Quanto o [atual] soma na caminhada, em ms: com a pessoa andando a pé ([movimento]), o tempo desde o último fixo
 * da [janela], até 3 s; senão, 0. Os primeiros segundos de uma caminhada (e de cada volta depois de parar) não
 * somam: é o tempo de ter certeza de que ela saiu do lugar (10 s ou mais, com o GPS ruim; 20 s sem o rumo medido).
 */
fun passoDeCaminhada(janela: Janela, atual: Fixo): Long {
    val ultimo = janela.fixos.lastOrNull() ?: return 0
    return if (movimento(janela, atual) != Movimento.ANDANDO) 0 else (atual.tempoMs - ultimo.tempoMs).coerceIn(0, PASSO_MAX_MS)
}

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
