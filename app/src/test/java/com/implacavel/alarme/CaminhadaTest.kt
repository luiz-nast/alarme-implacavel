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

    @Test
    fun andandoAPeConta() {
        val antes = fixo(0)
        assertEquals(Movimento.ANDANDO, movimento(antes, fixo(1_000, velocidade = 1.3f)))
        assertEquals(1_000L, passoDeCaminhada(antes, fixo(1_000, velocidade = 1.3f)))
        assertEquals(1_000L, passoDeCaminhada(antes, fixo(1_000, velocidade = 4.0f))) // corrida leve
        assertEquals(1_000L, passoDeCaminhada(antes, fixo(1_000, velocidade = 0.5f))) // passo lento
        // Parado, a velocidade do GPS treme perto de zero
        assertEquals(Movimento.PARADO, movimento(antes, fixo(1_000, velocidade = 0.3f)))
        assertEquals(0L, passoDeCaminhada(antes, fixo(1_000, velocidade = 0.3f)))
        // O primeiro fixo não tem de onde medir o tempo
        assertEquals(0L, passoDeCaminhada(null, fixo(1_000, velocidade = 1.3f)))
    }

    @Test
    fun soOTempoAndandoSoma() {
        // Um fixo por segundo: 10 s andando, 5 s parado no farol, mais 5 s andando
        val fixos = (0..20).map { t -> fixo(t * 1_000L, velocidade = if (t in 11..15) 0f else 1.3f) }
        val andado = fixos.zipWithNext { anterior, atual -> passoDeCaminhada(anterior, atual) }.sum()
        assertEquals(15_000L, andado)
    }

    @Test
    fun localizacaoFalsaNaoConta() {
        val atual = fixo(1_000, velocidade = 1.3f, falso = true)
        assertFalse(fixoValido(atual))
        assertEquals(Movimento.FALSO, movimento(fixo(0), atual))
        assertEquals(0L, passoDeCaminhada(fixo(0), atual))
    }

    @Test
    fun gpsImprecisoNaoConta() {
        // Dentro de casa ou entre prédios o GPS erra dezenas de metros
        assertEquals(Movimento.IMPRECISO, movimento(fixo(0), fixo(1_000, precisao = 40f, velocidade = 1.3f)))
        assertEquals(0L, passoDeCaminhada(fixo(0), fixo(1_000, precisao = 25.5f, velocidade = 1.3f)))
        assertEquals(0L, passoDeCaminhada(fixo(0), fixo(1_000, precisao = null, velocidade = 1.3f))) // sem precisão
        assertEquals(1_000L, passoDeCaminhada(fixo(0), fixo(1_000, precisao = 25f, velocidade = 1.3f))) // no limite, vale
        assertTrue(fixoValido(fixo(0, precisao = 25f)))
    }

    @Test
    fun deCarroOuBicicletaNaoConta() {
        assertEquals(Movimento.RAPIDO_DEMAIS, movimento(fixo(0), fixo(1_000, velocidade = 12f)))
        assertEquals(0L, passoDeCaminhada(fixo(0), fixo(1_000, velocidade = 12f))) // carro, 43 km/h
        assertEquals(0L, passoDeCaminhada(fixo(0), fixo(1_000, velocidade = 5f))) // bicicleta, 18 km/h
        // Sem a velocidade do GPS, pelo deslocamento: 15 m em 1 s
        assertEquals(Movimento.RAPIDO_DEMAIS, movimento(fixo(0), fixo(1_000, norteM = 15.0)))
    }

    @Test
    fun buracoNoSinalSomaNoMaximo3Segundos() {
        assertEquals(3_000L, passoDeCaminhada(fixo(0), fixo(10_000, velocidade = 1.3f)))
        assertEquals(2_500L, passoDeCaminhada(fixo(0), fixo(2_500, velocidade = 1.3f)))
        assertEquals(0L, passoDeCaminhada(fixo(5_000), fixo(4_000, velocidade = 1.3f))) // relógio pra trás não desconta
    }

    @Test
    fun semVelocidadeDoGpsUsaODeslocamento() {
        // 1,3 m em 1 s, sem velocidade medida: andando
        assertEquals(1.3f, velocidade(fixo(0), fixo(1_000, norteM = 1.3))!!, 0.01f)
        assertEquals(Movimento.ANDANDO, movimento(fixo(0), fixo(1_000, norteM = 1.3)))
        assertEquals(1_000L, passoDeCaminhada(fixo(0), fixo(1_000, norteM = 1.3)))
        // No mesmo lugar: parado
        assertEquals(0L, passoDeCaminhada(fixo(0), fixo(1_000)))
        // Velocidade medida com incerteza grande (3 m/s) não vale: conta o deslocamento
        assertEquals(1_000L, passoDeCaminhada(fixo(0), fixo(1_000, norteM = 1.3, velocidade = 0f, precisaoVelocidade = 3f)))
        // A medida confiável vale mais que o deslocamento (parado, o ponto do GPS treme uns metros)
        assertEquals(0L, passoDeCaminhada(fixo(0), fixo(1_000, norteM = 2.0, velocidade = 0.1f, precisaoVelocidade = 0.4f)))
        // Sem velocidade e sem anterior, não dá pra saber; com o mesmo tempo, também não
        assertNull(velocidade(null, fixo(1_000)))
        assertNull(velocidade(fixo(1_000), fixo(1_000, norteM = 1.3)))
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
    fun caminhada18MinutosETeste1Minuto() {
        val alarme = Alarme(1, 7, 0, caminhada = true)
        val teste = Alarme.teste(caminhada = true)
        assertEquals(18 * 60_000L, metaCaminhada(alarme))
        assertEquals(60_000L, metaCaminhada(teste))
        assertEquals(7 * 60_000L, prazoSair(alarme))
        assertEquals(20_000L, prazoSair(teste))
        assertEquals(18 * 60_000L, metaCameraSemCaminhada(alarme))
        assertEquals(30_000L, metaCameraSemCaminhada(teste))
    }

    @Test
    fun gpsSoTemDefeitoSemOuvirSateliteOuMuitoTempoSemFixo() {
        // Dentro de casa: o GPS ouve satélites fracos, sem fixo. Não é defeito: tem que ir pra rua
        assertFalse(gpsComDefeito(surdoMs = 0, semFixoMs = 10 * 60_000L))
        // Sem ouvir satélite nenhum por 5 min: defeito
        assertFalse(gpsComDefeito(surdoMs = SEM_GPS_MS - 1, semFixoMs = SEM_GPS_MS - 1))
        assertTrue(gpsComDefeito(surdoMs = SEM_GPS_MS, semFixoMs = SEM_GPS_MS))
        // Ouvindo, mas sem fixo nenhum por 15 min: também, pra ninguém ficar preso num GPS que nunca acha o lugar
        assertFalse(gpsComDefeito(surdoMs = 0, semFixoMs = SEM_FIXO_MAX_MS - 1))
        assertTrue(gpsComDefeito(surdoMs = 0, semFixoMs = SEM_FIXO_MAX_MS))
    }

    @Test
    fun bipeDeParadoSoDepoisDoPrazoPraSair() {
        val prazo = 420_000L // 7 min depois do começo da etapa
        // No prazo, nada de bipe: a tela mostra quanto falta dele
        assertNull(segundosProBipe(agora = 400_000, ultimoPasso = 0, prazoAte = prazo))
        assertEquals("Você tem 6:32 pra começar a andar", avisoDoPrazo(agora = 28_000, prazoAte = prazo))
        assertEquals("Você tem 20 s pra começar a andar", avisoDoPrazo(agora = 400_000, prazoAte = prazo))
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
        assertEquals("18:00", tempoAndado(CAMINHADA_MS))
        assertEquals("1,3 m/s", textoDaVelocidade(1.34f))
        assertEquals("0,0 m/s", textoDaVelocidade(0f))
    }

    @Test
    fun soCaminhaComCertezaDeQueNaoChove() {
        assertTrue(podeCaminhar(localizacao = true, gpsLigado = true, chuva = false))
        assertFalse(podeCaminhar(localizacao = true, gpsLigado = true, chuva = true))
        assertFalse(podeCaminhar(localizacao = true, gpsLigado = true, chuva = null)) // sem internet: câmera
        assertFalse(podeCaminhar(localizacao = false, gpsLigado = true, chuva = false))
        assertFalse(podeCaminhar(localizacao = true, gpsLigado = false, chuva = false))
    }
}
