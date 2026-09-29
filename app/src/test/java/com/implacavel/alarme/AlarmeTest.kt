package com.implacavel.alarme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Regras de quando o alarme toca ([Alarme.proximoDisparo] e [Alarme.proximoToque]). */
class AlarmeTest {
    private val zona = ZoneId.of("America/Sao_Paulo")

    // 29/09/2026 é uma terça-feira
    private fun em(mes: Int, dia: Int, hora: Int, minuto: Int) = ZonedDateTime.of(2026, mes, dia, hora, minuto, 0, 0, zona)

    @Test
    fun umaVezMaisTardeNoMesmoDia() {
        assertEquals(em(9, 29, 22, 0), Alarme(1, 22, 0).proximoDisparo(em(9, 29, 21, 30)))
    }

    @Test
    fun horarioQueJaPassouFicaParaAmanha() {
        assertEquals(em(9, 30, 7, 0), Alarme(1, 7, 0).proximoDisparo(em(9, 29, 21, 30)))
    }

    @Test
    fun exatamenteAgoraFicaParaAmanha() {
        assertEquals(em(9, 30, 7, 0), Alarme(1, 7, 0).proximoDisparo(em(9, 29, 7, 0)))
    }

    @Test
    fun diasUteisNaSextaDeNoitePulaParaSegunda() {
        val alarme = Alarme(1, 7, 0, dias = setOf(1, 2, 3, 4, 5))
        assertEquals(em(10, 5, 7, 0), alarme.proximoDisparo(em(10, 2, 21, 0)))
    }

    @Test
    fun soDomingo() {
        val alarme = Alarme(1, 9, 30, dias = setOf(7))
        assertEquals(em(10, 4, 9, 30), alarme.proximoDisparo(em(9, 29, 10, 0)))
    }

    @Test
    fun sonecaPendenteContaComoProximoToque() {
        val agora = em(9, 29, 6, 45)
        val daquiCinco = agora.plusMinutes(5)
        val adiado = Alarme(1, 6, 45, ativo = false, sonecaAte = daquiCinco.toInstant().toEpochMilli())
        assertEquals(daquiCinco, adiado.proximoToque(agora))
        assertNull(adiado.copy(sonecaAte = null).proximoToque(agora))
        // Soneca que já passou não conta; vale o próximo disparo normal
        assertEquals(em(9, 30, 6, 45), adiado.copy(ativo = true).proximoToque(agora.plusMinutes(10)))
    }
}
