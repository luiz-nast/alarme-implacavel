package com.implacavel.alarme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun rostoDeFrenteComOlhosAbertosEstaOlhando() {
        assertEquals(Leitura.OLHANDO, classificarRosto(5f, -3f, 0.9f, 0.8f))
    }

    @Test
    fun olhoFechadoOuSemInformacaoNaoConta() {
        assertEquals(Leitura.OLHOS_FECHADOS, classificarRosto(0f, 0f, 0.9f, 0.2f))
        assertEquals(Leitura.OLHOS_FECHADOS, classificarRosto(0f, 0f, null, 0.9f))
    }

    @Test
    fun rostoVirandoNaoConta() {
        assertEquals(Leitura.DE_LADO, classificarRosto(35f, 0f, 0.9f, 0.9f))
        assertEquals(Leitura.DE_LADO, classificarRosto(0f, -30f, 0.9f, 0.9f))
        assertEquals(Leitura.OLHANDO, classificarRosto(22f, -22f, 0.9f, 0.9f)) // até 25° ainda conta
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
    fun alarmePede20MinutosETeste30Segundos() {
        assertEquals(20 * 60_000L, metaOlhar(Alarme(1, 7, 0)))
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
}
