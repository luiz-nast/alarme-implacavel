// Blocos da tela principal: cabeçalho, permissões e testes (que abrem e fecham), cartão da música e cartão de cada alarme.
package com.implacavel.alarme

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZonedDateTime

@Composable
fun Cabecalho(alarmes: List<Alarme>, agora: ZonedDateTime) {
    val proximo = alarmes.filter { it.ativo }.map { it.proximoDisparo(agora) }.minByOrNull { it.toEpochSecond() }
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

/**
 * Um bloco da tela principal que abre e fecha num toque: fechado, só o [titulo] e o [resumo] (em vermelho com
 * [alerta]); aberto, o [conteudo]. Começa aberto com [alerta], e abre sozinho quando o alerta aparece.
 */
@Composable
fun SecaoRecolhivel(titulo: String, resumo: String, alerta: Boolean, conteudo: @Composable ColumnScope.() -> Unit) {
    var aberta by rememberSaveable { mutableStateOf(alerta) }
    LaunchedEffect(alerta) { if (alerta) aberta = true }
    val cores = if (alerta) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        CardDefaults.cardColors()
    }
    Card(colors = cores, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { aberta = !aberta }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(titulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(resumo, style = MaterialTheme.typography.bodyMedium)
            }
            Text(if (aberta) "▴" else "▾", fontSize = 22.sp)
        }
        AnimatedVisibility(aberta) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = conteudo)
        }
    }
}

/**
 * As permissões do alarme ([poderes]: cada uma e se está liberada), recolhidas: fechado, só "✓ Todas
 * liberadas" ou quantas faltam; aberto, todas, cada uma com ✓ ou com o botão "Liberar". Faltando alguma,
 * aparece aberto e em vermelho.
 */
@Composable
fun CartaoPoderes(poderes: Map<Poder, Boolean>, onLiberar: (Poder) -> Unit) {
    val faltam = poderes.count { !it.value }
    val resumo = when (faltam) {
        0 -> "✓ Todas liberadas: o alarme toca bloqueado, no silencioso e no Não Perturbe"
        1 -> "⚠ Falta 1 pro alarme ser implacável"
        else -> "⚠ Faltam $faltam pro alarme ser implacável"
    }
    SecaoRecolhivel("Permissões", resumo, alerta = faltam > 0) {
        poderes.forEach { (poder, liberado) ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(poder.titulo, fontWeight = FontWeight.SemiBold)
                    Text(poder.explicacao, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(8.dp))
                if (liberado) Text("✓", fontSize = 20.sp, fontWeight = FontWeight.Bold) else Button(onClick = { onLiberar(poder) }) { Text("Liberar") }
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

/**
 * Os botões de teste (tocam em 10 s), recolhidos: o alarme de sempre e o despertador com conversa e caminhada,
 * e se a IA do celular está pronta ([iaPronta]: o modelo copiado pro celular e memória livre).
 */
@Composable
fun SecaoTestes(iaPronta: Boolean, onTestar: () -> Unit, onTestarCaminhada: () -> Unit) {
    SecaoRecolhivel("Testes", "Tocam em 10 s, pra conferir o alarme", alerta = false) {
        OutlinedButton(onClick = onTestar, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Testar o alarme de sempre") }
        OutlinedButton(onClick = onTestarCaminhada, modifier = Modifier.fillMaxWidth()) { Text("Testar conversa e caminhada") }
        Text(
            if (iaPronta) "IA do celular pronta (Gemma 4 E2B, sem internet)" else "IA do celular sem o modelo: o teste com conversa vira o alarme de sempre",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Música que toca em todos os alarmes; sem escolha, vale o toque de alarme do celular. */
@Composable
fun CartaoMusica(nome: String?, onEscolher: () -> Unit, onTirar: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Música do alarme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    nome ?: "Toque de alarme do celular",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (nome != null) TextButton(onClick = onTirar) { Text("Tirar") }
            Button(onClick = onEscolher) { Text("Escolher") }
        }
    }
}

@Composable
fun CartaoAlarme(alarme: Alarme, agora: ZonedDateTime, onLigar: (Boolean) -> Unit, onClick: () -> Unit) {
    val corHorario = if (alarme.ativo) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(alarme.horario, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = corHorario)
                Text(
                    listOfNotNull(alarme.rotulo.ifBlank { null }, resumoDias(alarme.dias)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    when {
                        alarme.remedio -> "lembrete: toque suave"
                        alarme.caminhada -> "conversa e caminhada: STOP, IA e 18 min andando"
                        else -> "atividade: STOP + 40 min de câmera"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (alarme.ativo) {
                    Text(
                        "Toca ${tempoAte(agora, alarme.proximoDisparo(agora))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = alarme.ativo, onCheckedChange = onLigar)
        }
    }
}
