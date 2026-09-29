// Blocos da tela principal: cabeçalho, cartão de permissões e cartão de cada alarme.
package com.implacavel.alarme

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZonedDateTime

@Composable
fun Cabecalho(alarmes: List<Alarme>, agora: ZonedDateTime) {
    val proximo = alarmes.mapNotNull { it.proximoToque(agora) }.minByOrNull { it.toEpochSecond() }
    Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Text("Alarme Implacável", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            if (proximo == null) {
                "Nenhum alarme ligado"
            } else {
                "Próximo: ${NOME_CURTO_DIA.getValue(proximo.dayOfWeek.value)}, " +
                    "${hhmm(proximo.toLocalTime())} · ${tempoAte(agora, proximo)}"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Lista as permissões que faltam, cada uma com um botão "Liberar". Some quando está tudo liberado. */
@Composable
fun CartaoPoderes(poderes: Map<Poder, Boolean>, onLiberar: (Poder) -> Unit) {
    val faltando = Poder.entries.filter { poderes[it] != true }
    val cores = if (faltando.isEmpty()) {
        CardDefaults.cardColors()
    } else {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Card(colors = cores, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (faltando.isEmpty()) {
                Text("✓ Todos os poderes liberados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "O alarme toca com a tela bloqueada, no modo silencioso e passando pelo Não Perturbe.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val palavra = if (faltando.size == 1) "poder" else "poderes"
                Text(
                    "Faltam ${faltando.size} $palavra pro alarme ser implacável",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                faltando.forEach { poder ->
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(poder.titulo, fontWeight = FontWeight.SemiBold)
                            Text(poder.explicacao, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onLiberar(poder) }) { Text("Liberar") }
                    }
                }
            }
            if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                Text(
                    "Samsung: em Configurações › Bateria › Limites de uso em segundo plano, confira que o app " +
                        "não está em \"Apps em suspensão\" nem em \"Apps em suspensão profunda\".",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
fun CartaoAlarme(
    alarme: Alarme,
    agora: ZonedDateTime,
    onLigar: (Boolean) -> Unit,
    onCancelarSoneca: () -> Unit,
    onClick: () -> Unit,
) {
    val corHorario = if (alarme.ativo) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(alarme.horario, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = corHorario)
                Text(
                    listOfNotNull(alarme.rotulo.ifBlank { null }, resumoDias(alarme.dias)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val extras = listOfNotNull(
                    "desafio pra desligar".takeIf { alarme.desafio },
                    "volume máximo".takeIf { alarme.volumeMaximo },
                )
                if (extras.isNotEmpty()) {
                    Text(extras.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (alarme.ativo) {
                    Text(
                        "Toca ${tempoAte(agora, alarme.proximoDisparo(agora))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                alarme.sonecaPendente(agora)?.let { soneca ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Soneca até ${hhmm(soneca.toLocalTime())}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = onCancelarSoneca) { Text("Cancelar soneca") }
                    }
                }
            }
            Switch(checked = alarme.ativo, onCheckedChange = onLigar)
        }
    }
}
