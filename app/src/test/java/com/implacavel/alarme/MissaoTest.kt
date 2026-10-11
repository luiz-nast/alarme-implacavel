package com.implacavel.alarme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** Regras da missão pra desligar (RegrasMissao.kt). */
class MissaoTest {

    @Test
    fun reconheceStopComSotaque() {
        for (frase in listOf("stop", "STOP!", "Stop.", "estop", "istópi", "stopi", "bus stop", "para, stop")) {
            assertTrue(frase, disseStop(frase))
        }
    }

    @Test
    fun ignoraOutrasPalavras() {
        for (frase in listOf("", "top", "bom dia", "para", "pare", "desliga")) {
            assertFalse(frase, disseStop(frase))
        }
    }

    @Test
    fun olhoMeioAbertoDeQuemEstaAcordandoJaConta() {
        assertEquals(Leitura.OLHANDO, classificarRosto(0.9f, 0.8f))
        assertEquals(Leitura.OLHANDO, classificarRosto(0.5f, 0.45f)) // sonolento, sem arregalar
        assertEquals(Leitura.OLHANDO, classificarRosto(null, 0.5f)) // um olho só visível
        assertEquals(Leitura.OLHOS_FECHADOS, classificarRosto(0.4f, 0.35f)) // quase dormindo: não conta
    }

    @Test
    fun olhoFechadoOuSemInformacaoNaoConta() {
        assertEquals(Leitura.OLHOS_FECHADOS, classificarRosto(0.05f, 0.1f))
        assertEquals(Leitura.OLHOS_FECHADOS, classificarRosto(0.3f, 0.2f))
        assertEquals(Leitura.OLHOS_FECHADOS, classificarRosto(null, null))
    }

    @Test
    fun anelSoSobeOlhandoENuncaDesce() {
        var olhado = 0L
        repeat(50) { olhado = avancarOlhar(olhado, olhando = true, passoMs = 100) }
        assertEquals(5_000L, olhado)
        // Sem olhos abertos agora, o anel para na hora e fica onde está
        repeat(30) { olhado = avancarOlhar(olhado, olhando = false, passoMs = 100) }
        assertEquals(5_000L, olhado)
        // Conta o tempo real entre duas conferências, não um passo fixo
        assertEquals(5_130L, avancarOlhar(olhado, olhando = true, passoMs = 130))
    }

    @Test
    fun alarmePede40MinutosETeste30Segundos() {
        assertEquals(40 * 60_000L, metaOlhar(Alarme(1, 7, 0)))
        assertEquals(30_000L, metaOlhar(Alarme.teste()))
    }

    @Test
    fun contagemPraZerarApareceAos3Segundos() {
        assertNull(segundosParaZerar(0))
        assertNull(segundosParaZerar(2_999))
        assertEquals(17, segundosParaZerar(3_000))
        assertEquals(17, segundosParaZerar(3_500))
        assertEquals(16, segundosParaZerar(4_000))
        assertEquals(1, segundosParaZerar(19_500))
        assertEquals(0, segundosParaZerar(20_000))
    }

    @Test
    fun bipesSobemDoLevinhoAoMaximoDoCelular() {
        assertEquals(6, BIPES_ATE_ZERAR) // aos 3, 6, 9, 12, 15 e 18 s sem olhar
        assertTrue(BIPES_ATE_ZERAR * AVISO_SEM_OLHAR_MS < DESISTENCIA_MS)
        // Celular com 15 degraus de volume de alarme, como o Galaxy S24 FE: 20% … 100%
        assertEquals(listOf(3, 5, 8, 10, 13, 15), (1..BIPES_ATE_ZERAR).map { volumeDoBipe(it, 15) })
        assertEquals(listOf(1, 2, 4, 5, 6, 7), (1..BIPES_ATE_ZERAR).map { volumeDoBipe(it, 7) })
        assertEquals(1, volumeDoBipe(1, 2)) // nunca mudo
    }

    @Test
    fun youtubeSoAbreSiteNaPropriaPagina() {
        assertTrue(linkFicaNaPagina("https://m.youtube.com/watch?v=abc"))
        assertTrue(linkFicaNaPagina("HTTP://consent.youtube.com"))
        assertFalse(linkFicaNaPagina("intent://www.youtube.com/watch?v=abc#Intent;package=com.google.android.youtube;end"))
        assertFalse(linkFicaNaPagina("vnd.youtube:abc"))
        assertFalse(linkFicaNaPagina("market://details?id=com.google.android.youtube"))
        // Na página principal, só YouTube e Google; partes de dentro dela, qualquer site
        assertTrue(linkFicaNaPagina("https://accounts.google.com/signin"))
        assertFalse(linkFicaNaPagina("https://www.netflix.com"))
        assertFalse(linkFicaNaPagina("https://youtube.com.golpe.net"))
        assertTrue(linkFicaNaPagina("https://anuncio.exemplo.net/quadro", principal = false))
        assertFalse(linkFicaNaPagina("javascript:alert(1)", principal = false))
    }

    @Test
    fun saudacaoPelaHora() {
        assertEquals("Good morning", saudacao(5))
        assertEquals("Good afternoon", saudacao(12))
        assertEquals("Good evening", saudacao(19))
        assertEquals("Good evening", saudacao(2)) // madrugada
        assertEquals("Good morning! It's 5:05 AM. It's time for the activity check.", textoDaSaudacao(LocalTime.of(5, 5)))
        assertEquals("Good afternoon! It's 2:30 PM. It's time for the activity check.", textoDaSaudacao(LocalTime.of(14, 30)))
    }

    @Test
    fun vozAvisaACada10MinutosCompletos() {
        val meta = 40 * 60_000L
        assertNull(marcoCompletado(9 * 60_000L, 9 * 60_000L + 59_999, meta))
        assertEquals(10, marcoCompletado(9 * 60_000L + 59_500, 10 * 60_000L + 300, meta))
        assertNull(marcoCompletado(10 * 60_000L + 300, 10 * 60_000L + 1_300, meta)) // já avisou
        assertEquals(30, marcoCompletado(29 * 60_000L + 59_000, 30 * 60_000L, meta))
        assertNull(marcoCompletado(39 * 60_000L + 59_000, 40 * 60_000L, meta)) // nos 40 o alarme já desliga
        assertNull(marcoCompletado(29_000, 30_000, 30_000)) // teste: nenhum aviso
        assertEquals("20 minutes passed.", falaDosMinutos(20))
    }
}
