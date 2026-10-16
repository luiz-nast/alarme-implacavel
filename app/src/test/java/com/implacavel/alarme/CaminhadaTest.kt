package com.implacavel.alarme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
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
        var janela = emptyList<Fixo>()
        var soma = 0L
        var ultimo = Movimento.PARADO
        for (f in fixos) {
            ultimo = movimento(janela, f)
            soma += passoDeCaminhada(janela, f)
            janela = janelaDepois(janela, f)
        }
        return soma to ultimo
    }

    @Test
    fun andarDeVerdadeConta() {
        // 20 s a 1,3 m/s pro norte, um fixo por segundo, com a velocidade e o rumo do GPS: os 10 primeiros segundos
        // são pra ter certeza de que saiu do lugar
        val (andado, agora) = andar((0..20).map { t -> fixo(t * 1_000L, norteM = 1.3 * t, velocidade = 1.3f, precisaoVelocidade = 0.5f, rumo = 0f) })
        assertEquals(11_000L, andado)
        assertEquals(Movimento.ANDANDO, agora)
        // Corrida leve (sem a velocidade do GPS, só pelo deslocamento) também
        assertEquals(11_000L, andar((0..20).map { t -> fixo(t * 1_000L, norteM = 3.5 * t) }).first)
        // No passo lento, a certeza demora mais: o deslocamento tem que passar de 10 m
        assertEquals(9_000L, andar((0..20).map { t -> fixo(t * 1_000L, norteM = 0.9 * t) }).first)
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
        // Perto da janela, balançando o braço: o ponto pula 25 m (o GPS erra). O pulo recomeça a janela, e depois
        // dele o ponto fica no mesmo lugar
        val pulos = (0..40).map { t -> fixo(t * 1_000L, norteM = if (t < 5) 0.0 else 25.0, precisao = 18f, velocidade = 1.2f, precisaoVelocidade = 0.5f) }
        assertEquals(0L, andar(pulos).first)
        // Um pulo pequeno (6 m, sem a velocidade medida) não passa da incerteza (±18 m)
        assertEquals(0L, andar((0..40).map { t -> fixo(t * 1_000L, norteM = if (t < 5) 0.0 else 6.0, precisao = 18f) }).first)
        // O ponto escorregando 2 m/s pro norte, com o braço balançando de lado: o rumo medido não segue o caminho
        val escorregando = (0..40).map { t ->
            fixo(t * 1_000L, norteM = 2.0 * minOf(t, 15), precisao = 15f, velocidade = 1.2f, precisaoVelocidade = 0.5f, rumo = if (t % 2 == 0) 90f else 270f)
        }
        assertEquals(0L, andar(escorregando).first)
    }

    @Test
    fun indoEVoltandoNaoConta() {
        // 15 s pro norte e 15 s de volta, sem a velocidade do GPS: as duas metades da janela vão em sentidos opostos
        val (andado, _) = andar((0..30).map { t -> fixo(t * 1_000L, norteM = 1.3 * (if (t <= 15) t else 30 - t)) })
        // Só os segundos em que a janela ainda ia pro norte contam: a volta não
        assertTrue("$andado", andado <= 10_000L)
        // Dobrar uma esquina (90°) continua contando
        val esquina = (0..30).map { t -> if (t <= 15) fixo(t * 1_000L, norteM = 1.3 * t) else fixo(t * 1_000L, norteM = 19.5, lesteM = 1.3 * (t - 15)) }
        assertEquals(21_000L, andar(esquina).first)
    }

    @Test
    fun pertoDeParadoNaoConta() {
        // 0,4 m/s (arrastando o pé dentro de casa): não chega a 0,5 m/s
        assertEquals(0L, andar((0..30).map { t -> fixo(t * 1_000L, norteM = 0.4 * t) }).first)
        // Andando 1,3 m/s pelo deslocamento, mas o GPS mede bem que está parado: não conta
        assertEquals(0L, andar((0..20).map { t -> fixo(t * 1_000L, norteM = 1.3 * t, velocidade = 0.2f, precisaoVelocidade = 0.3f) }).first)
        // Medida com incerteza grande (3 m/s) não vale nem pra dizer que está parado: conta o deslocamento
        assertEquals(11_000L, andar((0..20).map { t -> fixo(t * 1_000L, norteM = 1.3 * t, velocidade = 0f, precisaoVelocidade = 3f) }).first)
    }

    @Test
    fun localizacaoFalsaNaoConta() {
        val atual = fixo(1_000, velocidade = 1.3f, falso = true)
        assertFalse(fixoValido(atual))
        assertEquals(Movimento.FALSO, movimento(listOf(fixo(0)), atual))
        assertEquals(0L, passoDeCaminhada(listOf(fixo(0)), atual))
        assertTrue(janelaDepois(emptyList(), atual).isEmpty())
    }

    @Test
    fun gpsImprecisoNaoConta() {
        // Dentro de casa ou entre prédios o GPS erra dezenas de metros
        assertEquals(Movimento.IMPRECISO, movimento(emptyList(), fixo(1_000, precisao = 25f, velocidade = 1.3f)))
        assertEquals(Movimento.IMPRECISO, movimento(emptyList(), fixo(1_000, precisao = null, velocidade = 1.3f))) // sem precisão
        assertTrue(fixoValido(fixo(0, precisao = 20f))) // no limite, vale
        assertFalse(fixoValido(fixo(0, precisao = 20.5f)))
        assertEquals(0L, andar((0..20).map { t -> fixo(t * 1_000L, norteM = 1.3 * t, precisao = 30f, velocidade = 1.3f) }).first)
    }

    @Test
    fun deCarroOuBicicletaNaoConta() {
        // Pela velocidade medida, já no primeiro fixo
        assertEquals(Movimento.RAPIDO_DEMAIS, movimento(emptyList(), fixo(1_000, velocidade = 12f))) // carro, 43 km/h
        assertEquals(0L, andar((0..20).map { t -> fixo(t * 1_000L, norteM = 5.0 * t, velocidade = 5f) }).first) // bicicleta, 18 km/h
        // Sem a velocidade do GPS, pelo deslocamento: 10 m por segundo
        val (andado, agora) = andar((0..20).map { t -> fixo(t * 1_000L, norteM = 10.0 * t) })
        assertEquals(0L, andado)
        assertEquals(Movimento.RAPIDO_DEMAIS, agora)
    }

    @Test
    fun buracoNoSinalSomaNoMaximo3Segundos() {
        // Andando 12 s, aí 6 s sem fixo (debaixo de uma marquise): o fixo depois do buraco soma só 3 s
        val janela = (0..12).map { t -> fixo(t * 1_000L, norteM = 1.3 * t) }.fold(emptyList<Fixo>()) { j, f -> janelaDepois(j, f) }
        val depois = fixo(18_000, norteM = 1.3 * 18)
        assertEquals(Movimento.ANDANDO, movimento(janela, depois))
        assertEquals(3_000L, passoDeCaminhada(janela, depois))
        // Relógio pra trás não desconta nem soma
        assertEquals(0L, passoDeCaminhada(janela, fixo(11_000, norteM = 1.3 * 11)))
    }

    @Test
    fun velocidadeDeVerdadeEJanela() {
        val janela = (0..9).map { t -> fixo(t * 1_000L, norteM = 1.3 * t) }.fold(emptyList<Fixo>()) { j, f -> janelaDepois(j, f) }
        // A média desde o fixo mais velho da janela, não a que o GPS mede (aqui, a do braço)
        assertEquals(1.3f, velocidade(janela, fixo(10_000, norteM = 13.0, velocidade = 3f))!!, 0.01f)
        // Nos primeiros segundos, sem janela, não dá pra saber
        assertNull(velocidade(listOf(fixo(0)), fixo(3_000, norteM = 4.0)))
        // A janela guarda só os válidos dos últimos 30 s
        val longa = (0..31).map { t -> fixo(t * 1_000L, norteM = 1.3 * t) }.fold(emptyList<Fixo>()) { j, f -> janelaDepois(j, f) }
        assertEquals((1..31).map { it * 1_000L }, longa.map { it.tempoMs })
        // Um pulo do ponto (30 m em 1 s) recomeça a janela dele
        assertEquals(listOf(32_000L), janelaDepois(longa, fixo(32_000, norteM = 1.3 * 31 + 30)).map { it.tempoMs })
        assertEquals("pulo", avaliar(longa, fixo(32_000, norteM = 1.3 * 31 + 30)).motivo)
    }

    @Test
    fun trilhaEmMetros() {
        val (leste, norte) = emMetros(fixo(0), fixo(0, norteM = 30.0, lesteM = 10.0))
        assertEquals(10.0, leste, 0.05)
        assertEquals(30.0, norte, 0.05)
    }

    /**
     * GPS de mentira, com o erro de verdade: um desvio que muda devagar (tempo de ~[tau] s, do tamanho da
     * [precisao]) mais um tremido de [branco] m. Andando a [v] m/s, dobrando uma esquina a cada 80 s; ou deitado
     * ([v] = null), com o braço balançando (velocidade ~1,2 m/s, rumo pra todo lado, ou nenhum) e o ponto pulando
     * 15 a 30 m a cada minuto. 10 min, um fixo por segundo, sempre igual ([semente]).
     */
    private fun simular(v: Double?, precisao: Double, semente: Long, tau: Double = 20.0, branco: Double = 0.7, comRumo: Boolean = true): List<Fixo> {
        val r = java.util.Random(semente)
        val sigma = precisao / 1.5
        val a = exp(-1 / tau)
        val passo = sigma * sqrt(1 - a * a)
        var ex = r.nextGaussian() * sigma
        var ey = r.nextGaussian() * sigma
        var x = 0.0
        var y = 0.0
        var rumo = r.nextDouble() * 360
        var ox = 0.0
        var oy = 0.0
        return (0 until 600).map { t ->
            if (v != null) {
                if (t > 0 && t % 80 == 0) rumo = (rumo + if (r.nextBoolean()) 90 else -90 + 360) % 360
                x += v * sin(Math.toRadians(rumo))
                y += v * cos(Math.toRadians(rumo))
            } else if (t > 0 && t % 60 == 0) {
                val ang = r.nextDouble() * 2 * PI
                val dist = 15 + r.nextDouble() * 15
                ox = dist * cos(ang)
                oy = dist * sin(ang)
            }
            val prec = (precisao * (0.8 + 0.4 * r.nextDouble())).toFloat()
            val f = if (v != null) {
                fixo(t * 1_000L, norteM = y + ey + r.nextGaussian() * branco, lesteM = x + ex + r.nextGaussian() * branco, precisao = prec,
                    velocidade = maxOf(0.0, v + r.nextGaussian() * 0.15).toFloat(), precisaoVelocidade = 0.5f,
                    rumo = ((rumo + r.nextGaussian() * 15 + 360) % 360).toFloat())
            } else {
                fixo(t * 1_000L, norteM = oy + ey + r.nextGaussian() * branco, lesteM = ox + ex + r.nextGaussian() * branco, precisao = prec,
                    velocidade = maxOf(0.0, 1.2 + r.nextGaussian() * 0.3).toFloat(), precisaoVelocidade = if (r.nextInt(3) == 0) 2.5f else 0.5f,
                    rumo = if (comRumo) (r.nextDouble() * 360).toFloat() else null)
            }
            ex = a * ex + r.nextGaussian() * passo
            ey = a * ey + r.nextGaussian() * passo
            f
        }
    }

    @Test
    fun naSimulacaoAndarContaEDeitadoNao() {
        // Andando com GPS bom ou médio: conta a maior parte do tempo (os 10 min têm 600 s)
        for ((v, precisao) in listOf(1.0 to 5.0, 1.3 to 10.0, 1.0 to 10.0)) {
            val andado = (1..3).map { andar(simular(v, precisao, semente = it * 31L)).first }.average()
            println("simulação: andando $v m/s com ±$precisao m conta ${andado / 6_000}%")
            assertTrue("andando $v m/s com ±$precisao m: ${andado / 6_000}%", andado >= 0.6 * 600_000)
        }
        // Deitado balançando o braço, com o GPS ruim de dentro de casa, com e sem o rumo medido: quase nada
        for (precisao in listOf(8.0, 12.0, 18.0)) {
            for (comRumo in listOf(true, false)) {
                val deitado = (1..3).maxOf { andar(simular(null, precisao, semente = it * 17L, comRumo = comRumo)).first }
                println("simulação: deitado com ±$precisao m (rumo: $comRumo) conta ${deitado / 6_000.0}%")
                assertTrue("deitado com ±$precisao m (rumo: $comRumo): ${deitado / 6_000.0}%", deitado <= 0.03 * 600_000)
            }
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
    fun caminhada16MinutosEPrazo4ETeste1Minuto() {
        val alarme = Alarme(1, 7, 0, caminhada = true)
        val teste = Alarme.teste(caminhada = true)
        assertEquals(16 * 60_000L, metaCaminhada(alarme))
        assertEquals(60_000L, metaCaminhada(teste))
        assertEquals(4 * 60_000L, prazoSair(alarme))
        assertEquals(20_000L, prazoSair(teste))
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
