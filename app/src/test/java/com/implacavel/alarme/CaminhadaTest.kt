package com.implacavel.alarme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras da caminhada da manhã (RegrasCaminhada.kt). */
class CaminhadaTest {

    /** 1 m de latitude, em graus (a Terra de raio 6.371 km). */
    private val metroEmGraus = 1 / 111_195.0

    /** Um fixo em São Paulo, [norteM] metros ao norte do ponto de partida, com GPS bom (±8 m). */
    private fun fixo(
        tempoMs: Long,
        norteM: Double = 0.0,
        precisao: Float? = 8f,
        velocidade: Float? = null,
        precisaoVelocidade: Float? = null,
        falso: Boolean = false,
    ) = Fixo(tempoMs, -23.55 + norteM * metroEmGraus, -46.63, precisao, velocidade, precisaoVelocidade, falso)

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
        // 20 s a 1,3 m/s, um fixo por segundo: os 7 primeiros são pra ter certeza de que saiu do lugar
        val (andado, agora) = andar((0..20).map { t -> fixo(t * 1_000L, norteM = 1.3 * t, velocidade = 1.3f) })
        assertEquals(14_000L, andado)
        assertEquals(Movimento.ANDANDO, agora)
        // Corrida leve e passo lento (sem a velocidade do GPS, só pelo deslocamento) também
        assertEquals(14_000L, andar((0..20).map { t -> fixo(t * 1_000L, norteM = 3.5 * t) }).first)
        // No passo lento, a certeza demora 2 s a mais (o deslocamento tem que passar da incerteza, 8 m)
        assertEquals(12_000L, andar((0..20).map { t -> fixo(t * 1_000L, norteM = 0.9 * t) }).first)
    }

    @Test
    fun bracoBalancandoNaCamaNaoConta() {
        // O caso do dono: deitado, balançando o braço. O GPS mede a velocidade do celular (1,2 m/s), mas o ponto
        // só treme em volta do mesmo lugar
        val tremendo = listOf(0.0, 1.5, -1.0, 0.5, 2.0, -0.5, 1.0, -1.5, 0.0, 1.8)
        val (andado, agora) = andar((0..40).map { t -> fixo(t * 1_000L, norteM = tremendo[t % tremendo.size], velocidade = 1.2f, precisaoVelocidade = 0.5f) })
        assertEquals(0L, andado)
        assertEquals(Movimento.PARADO, agora)
    }

    @Test
    fun pontoPulandoDentroDeCasaNaoConta() {
        // Perto da janela: o ponto pula 25 m (o GPS erra), mas a velocidade medida diz que está parado
        val pulos = (0..20).map { t -> fixo(t * 1_000L, norteM = if (t < 5) 0.0 else 25.0, precisao = 18f, velocidade = 0.1f, precisaoVelocidade = 0.3f) }
        assertEquals(0L, andar(pulos).first)
        // Sem a velocidade medida, o pulo ainda tem que passar da incerteza: 6 m com ±18 m não é sair do lugar
        assertEquals(0L, andar((0..20).map { t -> fixo(t * 1_000L, norteM = if (t < 5) 0.0 else 6.0, precisao = 18f) }).first)
    }

    @Test
    fun pertoDeParadoNaoConta() {
        // 0,4 m/s (arrastando o pé dentro de casa): não chega a 0,5 m/s
        assertEquals(0L, andar((0..20).map { t -> fixo(t * 1_000L, norteM = 0.4 * t) }).first)
        // Andando 1,3 m/s pelo deslocamento, mas o GPS mede bem que está parado: não conta
        assertEquals(0L, andar((0..20).map { t -> fixo(t * 1_000L, norteM = 1.3 * t, velocidade = 0.2f, precisaoVelocidade = 0.3f) }).first)
        // Medida com incerteza grande (3 m/s) não vale nem pra dizer que está parado: conta o deslocamento
        assertEquals(14_000L, andar((0..20).map { t -> fixo(t * 1_000L, norteM = 1.3 * t, velocidade = 0f, precisaoVelocidade = 3f) }).first)
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
        // Sem a velocidade do GPS, pelo deslocamento: 15 m por segundo
        val (andado, agora) = andar((0..20).map { t -> fixo(t * 1_000L, norteM = 15.0 * t) })
        assertEquals(0L, andado)
        assertEquals(Movimento.RAPIDO_DEMAIS, agora)
    }

    @Test
    fun buracoNoSinalSomaNoMaximo3Segundos() {
        // Andando 10 s, aí 6 s sem fixo (debaixo de uma marquise): o fixo depois do buraco soma só 3 s
        val antes = (0..10).map { t -> fixo(t * 1_000L, norteM = 1.3 * t) }
        val janela = antes.fold(emptyList<Fixo>()) { j, f -> janelaDepois(j, f) }
        val depois = fixo(16_000, norteM = 1.3 * 16)
        assertEquals(Movimento.ANDANDO, movimento(janela, depois))
        assertEquals(3_000L, passoDeCaminhada(janela, depois))
        // Relógio pra trás não desconta nem soma
        assertEquals(0L, passoDeCaminhada(janela, fixo(9_000, norteM = 1.3 * 9)))
    }

    @Test
    fun velocidadeDeVerdadePeloDeslocamento() {
        val janela = (0..9).map { t -> fixo(t * 1_000L, norteM = 1.3 * t) }.fold(emptyList<Fixo>()) { j, f -> janelaDepois(j, f) }
        // A média desde o fixo mais velho da janela, não a que o GPS mede (aqui, a do braço)
        assertEquals(1.3f, velocidade(janela, fixo(10_000, norteM = 13.0, velocidade = 3f))!!, 0.01f)
        // Nos primeiros segundos, sem janela, não dá pra saber
        assertNull(velocidade(listOf(fixo(0)), fixo(3_000, norteM = 4.0)))
        // A janela guarda só os válidos dos últimos 10 s
        assertEquals((0..10).map { it * 1_000L }, janelaDepois(janela, fixo(10_000, norteM = 13.0)).map { it.tempoMs })
        assertEquals((1..11).map { it * 1_000L }, janelaDepois(janelaDepois(janela, fixo(10_000)), fixo(11_000)).map { it.tempoMs })
    }

    @Test
    fun trilhaEmMetros() {
        val (leste, norte) = emMetros(fixo(0), fixo(0, norteM = 30.0))
        assertEquals(0.0, leste, 0.01)
        assertEquals(30.0, norte, 0.05)
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
