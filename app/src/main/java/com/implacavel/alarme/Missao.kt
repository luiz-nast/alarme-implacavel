// Missão pra desligar o alarme, em duas etapas mostradas pela AlarmeActivity:
// 1) FALAR: dizer "stop" (a música para); 2) OLHAR: olhar pra câmera de olhos abertos até fechar o anel.
// Enquanto a pessoa olha, a etapa 2 avisa o AlarmeService a cada segundo; sem aviso por DESISTENCIA_MS
// (não olhou, ou fechou a tela), a vigia do serviço religa a música e a missão volta pra etapa 1.
package com.implacavel.alarme

import android.Manifest
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Na etapa de falar a música alterna: alta pra acordar, baixa pra voz se destacar no microfone. */
private const val MUSICA_ALTA_MS = 5_000L
private const val MUSICA_BAIXA_MS = 4_000L
private const val VOLUME_ESCUTA = 0.25f

private const val PASSO_MS = 100L

/** De quanto em quanto tempo a etapa da câmera avisa o serviço que a pessoa está olhando. */
private const val AVISO_OLHANDO_MS = 1_000L

/** Etapa 1: ouvir "stop". Sem reconhecimento de voz disponível, aparece um botão no lugar. */
@Composable
fun EtapaFalar(onStop: () -> Unit) {
    val ctx = LocalContext.current
    val aoDizerStop by rememberUpdatedState(onStop)
    var ouvido by remember { mutableStateOf("") }
    var semVoz by remember { mutableStateOf(false) }
    var janelaDeEscuta by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            while (true) {
                janelaDeEscuta = false
                AlarmeService.volume(ctx, 1f)
                delay(MUSICA_ALTA_MS)
                janelaDeEscuta = true
                AlarmeService.volume(ctx, VOLUME_ESCUTA)
                delay(MUSICA_BAIXA_MS)
            }
        } finally {
            // Saiu da etapa ou fechou a tela: a música não pode ficar presa no volume baixo
            AlarmeService.volume(ctx, 1f)
        }
    }
    DisposableEffect(Unit) {
        val ouvinte = OuvinteStop(ctx, onOuviu = { ouvido = it }, onStop = { aoDizerStop() }, onIndisponivel = { semVoz = true })
        ouvinte.comecar()
        onDispose { ouvinte.parar() }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (janelaDeEscuta) "🎤 Fala agora!" else "Pra desligar, diga", color = Color.White, fontSize = 20.sp)
        Text("STOP", color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Black)
        Text(
            if (ouvido.isBlank()) "" else "Ouvi: \"$ouvido\"",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        if (semVoz) {
            Spacer(Modifier.height(12.dp))
            Text("Reconhecimento de voz indisponível", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            BotaoGrande("PARAR A MÚSICA", onStop)
        }
    }
}

/**
 * Etapa 2: câmera num círculo com um anel que enche enquanto a pessoa olha de olhos abertos.
 * [onOlhando] é chamado a cada [AVISO_OLHANDO_MS] enquanto ela olha (mantém a música calada).
 */
@Composable
fun EtapaOlhar(onOlhando: () -> Unit, onConcluiu: () -> Unit) {
    val ctx = LocalContext.current
    var leitura by remember { mutableStateOf(Leitura.SEM_ROSTO) }
    if (!Poderes.concedida(ctx, Manifest.permission.CAMERA) || leitura == Leitura.SEM_CAMERA) {
        // Sem câmera não dá pra conferir os olhos: desliga no botão
        BotaoGrande("DESLIGAR", onConcluiu, fundoClaro = true)
        return
    }
    val avisarOlhando by rememberUpdatedState(onOlhando)
    val concluir by rememberUpdatedState(onConcluiu)
    var progresso by remember { mutableFloatStateOf(0f) }
    var ultimaOlhada by remember { mutableLongStateOf(0L) } // último quadro com olhos abertos
    var olhando by remember { mutableStateOf(false) } // já com a tolerância a piscadas
    LaunchedEffect(Unit) {
        var ultimoAviso = 0L
        while (true) {
            delay(PASSO_MS)
            val agora = System.currentTimeMillis()
            val agoraOlhando = olhandoComTolerancia(agora, ultimaOlhada)
            if (agoraOlhando != olhando) {
                olhando = agoraOlhando
                Log.i(TAG, "Câmera: ${if (olhando) "olhando" else "parou de olhar ($leitura)"}, anel ${(progresso * 100).toInt()}%")
            }
            if (olhando && agora - ultimoAviso >= AVISO_OLHANDO_MS) {
                avisarOlhando()
                ultimoAviso = agora
            }
            progresso = avancarOlhar(progresso, olhando, PASSO_MS)
            if (progresso >= 1f) {
                Log.i(TAG, "Missão: anel completo, alarme desligado")
                concluir()
                break
            }
        }
    }

    // Enquanto conta como olhando, uma piscada não pinta a tela de vermelho
    val exibida = if (olhando) Leitura.OLHANDO else leitura
    val cor by animateColorAsState(corDaLeitura(exibida), label = "cor da leitura")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            CameraOlhos(Modifier.size(220.dp).clip(CircleShape)) { nova ->
                leitura = nova
                if (nova == Leitura.OLHANDO) ultimaOlhada = System.currentTimeMillis()
            }
            AnelProgresso(progresso, cor, Modifier.size(252.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(mensagemDaLeitura(exibida), color = cor, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Mais ${segundosRestantes(progresso)} s de olhos abertos", color = Color(0xFF3B0000), fontSize = 16.sp)
    }
}

/** Trilha fraca do anel inteiro + arco forte com o progresso, começando no topo. */
@Composable
private fun AnelProgresso(progresso: Float, cor: Color, modifier: Modifier) {
    Canvas(modifier) {
        val espessura = 12.dp.toPx()
        val canto = Offset(espessura / 2, espessura / 2)
        val area = Size(size.width - espessura, size.height - espessura)
        drawArc(cor.copy(alpha = 0.25f), 0f, 360f, useCenter = false, topLeft = canto, size = area, style = Stroke(espessura))
        drawArc(
            cor, -90f, 360f * progresso, useCenter = false, topLeft = canto, size = area,
            style = Stroke(espessura, cap = StrokeCap.Round),
        )
    }
}

private fun corDaLeitura(leitura: Leitura) = when (leitura) {
    Leitura.OLHANDO -> Color(0xFF2E7D32)
    Leitura.DE_LADO -> Color(0xFFEF8F00)
    else -> Color(0xFFC62828)
}

private fun mensagemDaLeitura(leitura: Leitura) = when (leitura) {
    Leitura.OLHANDO -> "Isso! Continua olhando"
    Leitura.DE_LADO -> "Olha direto pra câmera"
    Leitura.OLHOS_FECHADOS -> "Abre esses olhos!"
    Leitura.SEM_ROSTO, Leitura.SEM_CAMERA -> "Cadê você? Aproxima o rosto"
}
