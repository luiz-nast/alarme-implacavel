package com.implacavel.alarme

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Regras de quando o alarme toca ([Alarme.proximoDisparo]), nome, JSON e o alarme em andamento ([EmAndamento]). */
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
    fun nomeSemRotuloViraAlarme() {
        assertEquals("Alarme", Alarme(1, 7, 0, rotulo = " ").nome)
        assertEquals("Remédio", Alarme(1, 7, 0, rotulo = "Remédio").nome)
    }

    @Test
    fun jsonIdaEVolta() {
        // A foto do alarme em andamento (Ajustes.emAndamento) depende disto
        val alarme = Alarme(3, 6, 45, rotulo = "Academia", dias = setOf(1, 3, 5), ativo = false, missao = false, volumeForte = false)
        assertEquals(alarme, Alarme.deJson(alarme.paraJson()))
        assertEquals(Alarme.teste(), Alarme.deJson(Alarme.teste().paraJson()))
    }

    @Test
    fun fotoDoAlarmeEmAndamentoIdaEVolta() {
        val em = EmAndamento(Alarme(3, 6, 45, rotulo = "Academia"), desde = 1_790_000_000_000, musica = "content://musica/1", volume = 7)
        assertEquals(em, EmAndamento.deJson(em.paraJson()))
        val semMusica = em.copy(musica = null)
        assertEquals(semMusica, EmAndamento.deJson(semMusica.paraJson()))
    }

    @Test
    fun doisAlarmesJuntosFicamComAExigenciaMaior() {
        // Pré-alarme sem missão ainda tocando quando o alarme de verdade dispara
        val preAlarme = EmAndamento(Alarme(1, 6, 55, missao = false, volumeForte = false), desde = 0, musica = null, volume = 5)
        val junto = preAlarme.juntar(Alarme(2, 7, 0, missao = true, volumeForte = true), agora = 300_000)
        assertTrue(junto.alarme.missao)
        assertTrue(junto.alarme.volumeForte)
        assertEquals(1, junto.alarme.id) // segue o mesmo alarme, desde a mesma hora, com a música e o volume de antes
        assertEquals(0L, junto.desde)
        assertEquals(5, junto.volume)
        // Juntar com um alarme mais fraco não afrouxa nada
        assertEquals(junto, junto.juntar(Alarme(3, 7, 5, missao = false, volumeForte = false), agora = 600_000))
    }

    @Test
    fun alarmeDeVerdadeNoMeioDoTesteTomaOLugarDele() {
        val teste = EmAndamento(Alarme.teste(), desde = 0, musica = "content://musica/1", volume = 3)
        val junto = teste.juntar(Alarme(5, 7, 0), agora = 99)
        assertEquals(EmAndamento(Alarme(5, 7, 0), desde = 99, musica = "content://musica/1", volume = 3), junto)
        assertEquals(META_OLHAR_MS, metaOlhar(junto.alarme)) // 20 min, não os 30 s do teste
        assertEquals(11, junto.volumeTravado(maximo = 15)) // 70%, não os 50% do teste
        // Com as regras dele: alarme sem missão continua sem missão
        assertFalse(teste.juntar(Alarme(6, 7, 0, missao = false), agora = 99).alarme.missao)
        // O contrário: o teste no meio do alarme de verdade não muda nada
        val deVerdade = EmAndamento(Alarme(5, 7, 0, missao = false), desde = 0, musica = null, volume = 3)
        assertEquals(deVerdade, deVerdade.juntar(Alarme.teste(), agora = 99))
    }

    @Test
    fun volumeTravadoNoTesteNoForteENoNormal() {
        val normal = EmAndamento(Alarme(1, 7, 0, volumeForte = false), desde = 0, musica = null, volume = 4)
        assertEquals(4, normal.volumeTravado(maximo = 15)) // o volume de antes do alarme
        assertEquals(11, normal.juntar(Alarme(2, 7, 0, volumeForte = true), agora = 0).volumeTravado(maximo = 15)) // 70% de 15
        assertEquals(8, EmAndamento(Alarme.teste(), desde = 0, musica = null, volume = 3).volumeTravado(maximo = 15)) // 50% de 15
    }

    @Test
    fun jsonDeVersaoAntigaAindaLe() {
        // Versões anteriores gravavam "sonecaAte", que agora é ignorado
        val antigo = JSONObject(
            """{"id":2,"hora":7,"minuto":0,"rotulo":"","dias":[],"ativo":true,"missao":true,"volumeMaximo":true,"sonecaAte":1790000000000}""",
        )
        assertEquals(Alarme(2, 7, 0), Alarme.deJson(antigo))
    }
}
