package com.implacavel.alarme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Regras da caminhada da manhã (RegrasCaminhada.kt). */
class CaminhadaTest {

    /** 1 m de latitude, em graus (a Terra de raio 6.371 km). */
    private val metroEmGraus = 1 / 111_195.0

    /** Um fixo em São Paulo, [norteM] metros ao norte (e [lesteM] a leste) do ponto de partida, com GPS bom (±8 m). */
    private fun fixo(
        tempoMs: Long,
        norteM: Double = 0.0,
        precisao: Float? = 8f,
        velocidade: Float? = null,
        precisaoVelocidade: Float? = null,
        falso: Boolean = false,
        rumo: Float? = null,
        lesteM: Double = 0.0,
    ) = Fixo(
        tempoMs, -23.55 + norteM * metroEmGraus, -46.63 + lesteM * metroEmGraus / cos(Math.toRadians(23.55)),
        precisao, velocidade, precisaoVelocidade, falso, rumo,
    )

    /** Passa os [fixos] pela regra, um por um, como a tela: o tempo somado andando e o movimento do último. */
    private fun andar(fixos: List<Fixo>): Pair<Long, Movimento> {
        var janela = Janela()
        var soma = 0L
        var ultimo = Movimento.PARADO
        for (f in fixos) {
            ultimo = movimento(janela, f)
            soma += passoDeCaminhada(janela, f)
            janela = janelaDepois(janela, f)
        }
        return soma to ultimo
    }

    private fun janelaDe(fixos: List<Fixo>) = fixos.fold(Janela()) { j, f -> janelaDepois(j, f) }

    /** Andando a [v] m/s pro norte do segundo [de] ao [ate], com a velocidade e o rumo medidos pelo GPS. */
    private fun pro_norte(de: Int, ate: Int, v: Double, inicioM: Double = 0.0) =
        (de..ate).map { t -> fixo(t * 1_000L, norteM = inicioM + v * (t - de), velocidade = v.toFloat(), precisaoVelocidade = 0.5f, rumo = 0f) }

    @Test
    fun andarDeVerdadeConta() {
        // 20 s a 1,3 m/s pro norte, um fixo por segundo, com a velocidade e o rumo do GPS: os 10 primeiros segundos
        // são pra ter certeza de que saiu do lugar
        val (andado, agora) = andar(pro_norte(0, 20, 1.3))
        assertEquals(11_000L, andado)
        assertEquals(Movimento.ANDANDO, agora)
        assertEquals(11_000L, andar(pro_norte(0, 20, 3.5)).first) // corrida leve
        // No passo lento, a certeza demora mais: o deslocamento tem que passar de 10 m
        assertEquals(9_000L, andar(pro_norte(0, 20, 0.9)).first)
    }

    @Test
    fun semORumoDoGpsPedeMais() {
        // O GPS mede a velocidade mas não o rumo (ou nem a velocidade): só depois de 20 s e 20 m
        val semRumo = (0..40).map { t -> fixo(t * 1_000L, norteM = 1.3 * t, velocidade = 1.3f, precisaoVelocidade = 0.5f) }
        assertEquals(21_000L, andar(semRumo).first)
        assertEquals(21_000L, andar((0..40).map { t -> fixo(t * 1_000L, norteM = 1.3 * t) }).first)
        assertEquals("andando sem rumo", avaliar(janelaDe(semRumo.take(40)), semRumo[40]).motivo)
    }

    @Test
    fun bracoBalancandoNaCamaNaoConta() {
        // O caso do dono: deitado, balançando o braço. O GPS mede a velocidade do celular (1,2 m/s), mas o ponto
        // só treme em volta do mesmo lugar
        val tremendo = listOf(0.0, 1.5, -1.0, 0.5, 2.0, -0.5, 1.0, -1.5, 0.0, 1.8)
        val (andado, agora) = andar(
            (0..60).map { t -> fixo(t * 1_000L, norteM = tremendo[t % tremendo.size], velocidade = 1.2f, precisaoVelocidade = 0.5f, rumo = (t * 97 % 360).toFloat()) },
        )
        assertEquals(0L, andado)
        assertEquals(Movimento.PARADO, agora)
    }

    @Test
    fun pontoPulandoDentroDeCasaNaoConta() {
        // Perto da janela, balançando o braço: o ponto pula 25 m (o GPS erra) e fica lá. O pulo recomeça a janela, e
        // depois dele o ponto fica no mesmo lugar
        val pulos = (0..40).map { t -> fixo(t * 1_000L, norteM = if (t < 5) 0.0 else 25.0, precisao = 18f, velocidade = 1.2f, precisaoVelocidade = 0.5f) }
        assertEquals(0L, andar(pulos).first)
        // Um pulo pequeno (6 m, sem a velocidade medida) não passa da incerteza (±18 m)
        assertEquals(0L, andar((0..40).map { t -> fixo(t * 1_000L, norteM = if (t < 5) 0.0 else 6.0, precisao = 18f) }).first)
        // O ponto escorregando 2 m/s pro norte, com o braço balançando de lado: o rumo medido não segue o caminho
        val escorregando = (0..40).map { t ->
            fixo(t * 1_000L, norteM = 2.0 * minOf(t, 15), precisao = 15f, velocidade = 1.2f, precisaoVelocidade = 0.5f, rumo = if (t % 2 == 0) 90f else 270f)
        }
        assertEquals(0L, andar(escorregando).first)
        // Deitado parado, o ponto escorregando sozinho: o GPS mede perto de zero, mesmo com incerteza grande
        val sozinho = (0..40).map { t -> fixo(t * 1_000L, norteM = 1.2 * t, precisao = 15f, velocidade = 0.1f, precisaoVelocidade = 2.5f) }
        assertEquals(0L, andar(sozinho).first)
    }

    @Test
    fun umPuloDeUmSegundoAndandoNaoAtrapalha() {
        // Andando, um fixo sai 16 m pro lado (o GPS errou um segundo) e o seguinte volta pro caminho
        val comPulo = pro_norte(0, 60, 1.3).map { if (it.tempoMs == 30_000L) it.copy(longitude = it.longitude + 16 * metroEmGraus / cos(Math.toRadians(23.55))) else it }
        val semPulo = andar(pro_norte(0, 60, 1.3)).first
        assertEquals(51_000L, semPulo)
        assertTrue("${andar(comPulo).first}", andar(comPulo).first >= semPulo - 1_000L)
        // Já o pulo que fica (o seguinte confirma o lugar novo) recomeça a janela dos dois
        val janela = janelaDe(pro_norte(0, 20, 1.3))
        val pulo = fixo(21_000, norteM = 26.0, lesteM = 30.0)
        assertEquals(21, janelaDepois(janela, pulo).fixos.size)
        assertEquals(pulo, janelaDepois(janela, pulo).pulo)
        assertEquals(listOf(21_000L, 22_000L), janelaDepois(janelaDepois(janela, pulo), fixo(22_000, norteM = 27.0, lesteM = 30.0)).fixos.map { it.tempoMs })
    }

    @Test
    fun depoisDoFarolVoltaAContarLogo() {
        // Anda 30 s, para 20 s no farol (o GPS mede zero), anda mais 30 s: a volta conta como um começo (10 s)
        val ida = pro_norte(0, 30, 1.3)
        val farol = (31..50).map { t -> fixo(t * 1_000L, norteM = 39.0, velocidade = 0f, precisaoVelocidade = 0.3f, rumo = 0f) }
        val volta = pro_norte(51, 80, 1.3, inicioM = 39.0)
        var janela = Janela()
        val passos = (ida + farol + volta).map { f -> passoDeCaminhada(janela, f).also { janela = janelaDepois(janela, f) } }
        assertEquals(0L, passos.subList(31, 51).sum()) // parado no farol, nada
        assertTrue("${passos.subList(51, 81).sum()}", passos.subList(51, 81).sum() >= 18_000L)
    }

    @Test
    fun indoEVoltandoNaoConta() {
        // 15 s pro norte e 15 s de volta: as duas metades da janela vão em sentidos opostos
        val (andado, _) = andar(
            (0..30).map { t -> fixo(t * 1_000L, norteM = 1.3 * (if (t <= 15) t else 30 - t), velocidade = 1.3f, precisaoVelocidade = 0.5f, rumo = if (t <= 15) 0f else 180f) },
        )
        // Só os segundos em que a janela ainda ia pro norte contam: a volta não
        assertTrue("$andado", andado <= 10_000L)
        // Dobrar uma esquina (90°) continua contando
        val esquina = (0..30).map { t ->
            if (t <= 15) {
                fixo(t * 1_000L, norteM = 1.3 * t, velocidade = 1.3f, precisaoVelocidade = 0.5f, rumo = 0f)
            } else {
                fixo(t * 1_000L, norteM = 19.5, lesteM = 1.3 * (t - 15), velocidade = 1.3f, precisaoVelocidade = 0.5f, rumo = 90f)
            }
        }
        assertEquals(21_000L, andar(esquina).first)
    }

    @Test
    fun pertoDeParadoNaoConta() {
        // 0,4 m/s (arrastando o pé dentro de casa): não chega a 0,5 m/s
        assertEquals(0L, andar((0..30).map { t -> fixo(t * 1_000L, norteM = 0.4 * t) }).first)
        // Andando 1,3 m/s pelo deslocamento, mas o GPS mede bem que está parado: não conta
        assertEquals(0L, andar((0..20).map { t -> fixo(t * 1_000L, norteM = 1.3 * t, velocidade = 0.2f, precisaoVelocidade = 0.3f) }).first)
        // Medindo zero, mesmo com incerteza grande, na maior parte da janela: também não
        assertEquals(0L, andar((0..30).map { t -> fixo(t * 1_000L, norteM = 1.3 * t, velocidade = 0f, precisaoVelocidade = 3f) }).first)
    }

    @Test
    fun localizacaoFalsaNaoConta() {
        val atual = fixo(1_000, velocidade = 1.3f, falso = true)
        assertFalse(fixoValido(atual))
        assertEquals(Movimento.FALSO, movimento(janelaDe(listOf(fixo(0))), atual))
        assertEquals(0L, passoDeCaminhada(janelaDe(listOf(fixo(0))), atual))
        assertTrue(janelaDepois(Janela(), atual).fixos.isEmpty())
    }

    @Test
    fun gpsImprecisoNaoConta() {
        // Dentro de casa ou entre prédios o GPS erra dezenas de metros
        assertEquals(Movimento.IMPRECISO, movimento(Janela(), fixo(1_000, precisao = 25f, velocidade = 1.3f)))
        assertEquals(Movimento.IMPRECISO, movimento(Janela(), fixo(1_000, precisao = null, velocidade = 1.3f))) // sem precisão
        assertTrue(fixoValido(fixo(0, precisao = 20f))) // no limite, vale
        assertFalse(fixoValido(fixo(0, precisao = 20.5f)))
        assertEquals(0L, andar((0..20).map { t -> fixo(t * 1_000L, norteM = 1.3 * t, precisao = 30f, velocidade = 1.3f) }).first)
    }

    @Test
    fun deCarroOuBicicletaNaoConta() {
        // Pela velocidade medida, já no primeiro fixo
        assertEquals(Movimento.RAPIDO_DEMAIS, movimento(Janela(), fixo(1_000, velocidade = 12f))) // carro, 43 km/h
        assertEquals(0L, andar((0..20).map { t -> fixo(t * 1_000L, norteM = 5.0 * t, velocidade = 5f) }).first) // bicicleta, 18 km/h
        // Sem a velocidade do GPS, pelo deslocamento: 10 m por segundo
        val (andado, agora) = andar((0..20).map { t -> fixo(t * 1_000L, norteM = 10.0 * t) })
        assertEquals(0L, andado)
        assertEquals(Movimento.RAPIDO_DEMAIS, agora)
    }

    @Test
    fun buracoNoSinalSomaNoMaximo3Segundos() {
        // Andando 12 s, aí 6 s sem fixo (debaixo de uma marquise): o fixo depois do buraco soma só 3 s
        val janela = janelaDe(pro_norte(0, 12, 1.3))
        val depois = pro_norte(18, 18, 1.3, inicioM = 1.3 * 18).single()
        assertEquals(Movimento.ANDANDO, movimento(janela, depois))
        assertEquals(3_000L, passoDeCaminhada(janela, depois))
        // Relógio pra trás não desconta nem soma
        assertEquals(0L, passoDeCaminhada(janela, pro_norte(11, 11, 1.3, inicioM = 1.3 * 11).single()))
    }

    @Test
    fun velocidadeDeVerdadeEJanela() {
        val janela = janelaDe((0..9).map { t -> fixo(t * 1_000L, norteM = 1.3 * t) })
        // A média desde o fixo mais velho da janela, não a que o GPS mede (aqui, a do braço)
        assertEquals(1.3f, velocidade(janela, fixo(10_000, norteM = 13.0, velocidade = 3f))!!, 0.01f)
        // Nos primeiros segundos, sem janela, não dá pra saber
        assertNull(velocidade(janelaDe(listOf(fixo(0))), fixo(3_000, norteM = 4.0)))
        // A janela guarda só os válidos dos últimos 30 s
        val longa = janelaDe((0..31).map { t -> fixo(t * 1_000L, norteM = 1.3 * t) })
        assertEquals((1..31).map { it * 1_000L }, longa.fixos.map { it.tempoMs })
        assertEquals("pulo", avaliar(longa, fixo(32_000, norteM = 1.3 * 31 + 30)).motivo)
    }

    @Test
    fun trilhaEmMetros() {
        val (leste, norte) = emMetros(fixo(0), fixo(0, norteM = 30.0, lesteM = 10.0))
        assertEquals(10.0, leste, 0.05)
        assertEquals(30.0, norte, 0.05)
    }

    /** Como a pessoa está na simulação: andando, ou deitada de um dos jeitos de enganar. */
    private enum class Jeito { ANDANDO, ANDANDO_SEM_RUMO, BRACO, BRACO_SEM_RUMO, PARADO }

    /**
     * GPS de mentira, com o erro de verdade: um desvio que muda devagar (tempo de ~20 s, do tamanho da [precisao])
     * mais um tremido de 0,7 m. Andando a [v] m/s, dobrando uma esquina a cada 80 s; ou deitado, com o ponto pulando
     * 15 a 30 m a cada minuto, balançando o braço (velocidade ~1,2 m/s, rumo pra todo lado ou nenhum) ou parado
     * (velocidade perto de zero, às vezes com incerteza grande). 10 min, um fixo por segundo, sempre igual ([semente]).
     */
    private fun simular(jeito: Jeito, precisao: Double, semente: Long, v: Double = 1.0): List<Fixo> {
        val r = java.util.Random(semente)
        val sigma = precisao / 1.5
        val a = exp(-1 / 20.0)
        val passo = sigma * sqrt(1 - a * a)
        var ex = r.nextGaussian() * sigma
        var ey = r.nextGaussian() * sigma
        var x = 0.0
        var y = 0.0
        var rumo = r.nextDouble() * 360
        val andando = jeito == Jeito.ANDANDO || jeito == Jeito.ANDANDO_SEM_RUMO
        return (0 until 600).map { t ->
            if (andando) {
                if (t > 0 && t % 80 == 0) rumo = (rumo + if (r.nextBoolean()) 90 else 270) % 360
                x += v * sin(Math.toRadians(rumo))
                y += v * cos(Math.toRadians(rumo))
            } else if (t > 0 && t % 60 == 0) {
                val ang = r.nextDouble() * 2 * PI
                val dist = 15 + r.nextDouble() * 15
                x = dist * cos(ang)
                y = dist * sin(ang)
            }
            val prec = (precisao * (0.8 + 0.4 * r.nextDouble())).toFloat()
            val (vel, precVel, rumoMedido) = when (jeito) {
                Jeito.ANDANDO -> Triple(maxOf(0.0, v + r.nextGaussian() * 0.15), 0.5f, (rumo + r.nextGaussian() * 15 + 360) % 360)
                Jeito.ANDANDO_SEM_RUMO -> Triple(maxOf(0.0, v + r.nextGaussian() * 0.15), 0.5f, null)
                Jeito.BRACO -> Triple(maxOf(0.0, 1.2 + r.nextGaussian() * 0.3), if (r.nextInt(3) == 0) 2.5f else 0.5f, r.nextDouble() * 360)
                Jeito.BRACO_SEM_RUMO -> Triple(maxOf(0.0, 1.2 + r.nextGaussian() * 0.3), if (r.nextInt(3) == 0) 2.5f else 0.5f, null)
                Jeito.PARADO -> Triple(abs(r.nextGaussian() * 0.1), if (r.nextInt(3) == 0) 2.5f else 0.4f, null)
            }
            val f = fixo(
                t * 1_000L, norteM = y + ey + r.nextGaussian() * 0.7, lesteM = x + ex + r.nextGaussian() * 0.7, precisao = prec,
                velocidade = vel.toFloat(), precisaoVelocidade = precVel, rumo = rumoMedido?.toFloat(),
            )
            ex = a * ex + r.nextGaussian() * passo
            ey = a * ey + r.nextGaussian() * passo
            f
        }
    }

    /** Quanto dos 10 min a regra contou, em % (a média de 3 sementes, ou a pior com [pior]). */
    private fun contou(jeito: Jeito, precisao: Double, v: Double = 1.0, pior: Boolean = false): Double {
        val r = (1..3).map { andar(simular(jeito, precisao, semente = it * 31L, v = v)).first / 6_000.0 }
        return (if (pior) r.max() else r.average()).also { println("simulação: $jeito ${v}m/s ±${precisao}m conta ${"%.1f".format(it)}%") }
    }

    @Test
    fun naSimulacaoAndarContaEDeitadoNao() {
        // Andando com GPS bom ou médio: conta a maior parte do tempo; sem o rumo medido, menos, mas conta
        assertTrue(contou(Jeito.ANDANDO, 5.0) >= 85)
        assertTrue(contou(Jeito.ANDANDO, 10.0, v = 1.3) >= 85)
        assertTrue(contou(Jeito.ANDANDO, 10.0, v = 0.8) >= 55)
        assertTrue(contou(Jeito.ANDANDO, 15.0) >= 55)
        assertTrue(contou(Jeito.ANDANDO_SEM_RUMO, 10.0, v = 1.3) >= 45)
        // Deitado, com o GPS de dentro de casa: balançando o braço ou parado, quase nada
        for (precisao in listOf(8.0, 12.0, 18.0)) {
            assertTrue(contou(Jeito.BRACO, precisao, pior = true) <= 3)
            assertTrue(contou(Jeito.PARADO, precisao, pior = true) <= 1)
            // Sem o rumo medido, a regra mais dura ainda deixa passar um pouco
            assertTrue(contou(Jeito.BRACO_SEM_RUMO, precisao, pior = true) <= 10)
        }
    }

    @Test
    fun distanciaPeloHaversine() {
        val a = Fixo(0, -23.55, -46.63, 5f)
        assertEquals(0.0, distanciaM(a, a), 0.001)
        assertEquals(111.19, distanciaM(a, a.copy(latitude = -23.549)), 0.05) // 0,001° de latitude
        // 0,001° de longitude encolhe com o cosseno da latitude (em São Paulo, 0,92)
        assertEquals(101.93, distanciaM(a, a.copy(longitude = -46.629)), 0.05)
    }

    @Test
    fun caminhada16MinutosEPrazo4ETeste8Minutos() {
        val alarme = Alarme(1, 7, 0, caminhada = true)
        val teste = Alarme.teste(caminhada = true)
        assertEquals(16 * 60_000L, metaCaminhada(alarme))
        assertEquals(8 * 60_000L, metaCaminhada(teste))
        assertEquals(4 * 60_000L, prazoSair(alarme))
        assertEquals(30_000L, prazoSair(teste))
    }

    @Test
    fun bipeDeParadoSoDepoisDoPrazoPraSair() {
        val prazo = 240_000L // 4 min depois do começo da etapa
        // No prazo, nada de bipe: a tela mostra quanto falta dele
        assertNull(segundosProBipe(agora = 220_000, ultimoPasso = 0, prazoAte = prazo))
        assertEquals("Você tem 3:32 pra começar a andar", avisoDoPrazo(agora = 28_000, prazoAte = prazo))
        assertEquals("Você tem 20 s pra começar a andar", avisoDoPrazo(agora = 220_000, prazoAte = prazo))
        assertNull(avisoDoPrazo(agora = prazo, prazoAte = prazo))
        // Parado desde antes do fim do prazo: o 1º bipe 10 s depois dele
        assertEquals(prazo + 10_000, primeiroBipeParado(ultimoPasso = 100_000, prazoAte = prazo))
        assertEquals(10, segundosProBipe(agora = prazo, ultimoPasso = 100_000, prazoAte = prazo))
        assertEquals(1, segundosProBipe(agora = prazo + 9_500, ultimoPasso = 100_000, prazoAte = prazo))
        assertEquals(0, segundosProBipe(agora = prazo + 30_000, ultimoPasso = 100_000, prazoAte = prazo)) // já bipando
        // Parou de andar depois do prazo: 10 s depois do último passo
        assertEquals(prazo + 15_000, primeiroBipeParado(ultimoPasso = prazo + 5_000, prazoAte = prazo))
        assertEquals(7, segundosProBipe(agora = prazo + 8_000, ultimoPasso = prazo + 5_000, prazoAte = prazo))
    }

    @Test
    fun textosDaTela() {
        assertEquals("7:12", tempoAndado(432_999)) // o que já foi: não arredonda pra cima
        assertEquals("0:28", tempoAndado(28_400))
        assertEquals("16:00", tempoAndado(CAMINHADA_MS))
        assertEquals("1,3 m/s", textoDaVelocidade(1.34f))
        assertEquals("0,0 m/s", textoDaVelocidade(0f))
    }
}
