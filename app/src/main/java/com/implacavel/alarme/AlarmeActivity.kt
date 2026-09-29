package com.implacavel.alarme

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.random.Random

/**
 * Tela do alarme tocando. Aberta pela notificação em tela cheia do [AlarmeService]; aparece por
 * cima da tela de bloqueio, acende a tela e fecha sozinha quando o alarme para.
 */
class AlarmeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acordarTela()
        enableEdgeToEdge()
        setContent {
            TemaImplacavel(escuro = true) {
                val alarme by AlarmeService.tocando.collectAsStateWithLifecycle()
                BackHandler { /* o botão voltar não desliga o alarme */ }
                LaunchedEffect(alarme) { if (alarme == null) finish() }
                alarme?.let {
                    TelaAlarme(
                        alarme = it,
                        onDesligar = { AlarmeService.parar(this) },
                        onAdiar = { AlarmeService.adiar(this) },
                    )
                }
            }
        }
    }

    // Abaixar ou silenciar pelos botões laterais não cala o alarme
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE || super.onKeyDown(keyCode, event)

    private fun acordarTela() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun TelaAlarme(alarme: Alarme, onDesligar: () -> Unit, onAdiar: () -> Unit) {
    var agora by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            agora = LocalTime.now()
            delay(1_000)
        }
    }
    val escala by rememberInfiniteTransition(label = "pulso").animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "escala",
    )
    // Coluna rolável com altura mínima da tela: centraliza quando cabe e rola em celulares pequenos
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFB71C1C), Color(0xFF3B0000), Color.Black))),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(24.dp))
                Icon(
                    painterResource(R.drawable.ic_alarme),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(72.dp).scale(escala),
                )
                Text(hhmm(agora), color = Color.White, fontSize = 84.sp, fontWeight = FontWeight.Bold)
                Text(alarme.rotulo.ifBlank { "Alarme" }, color = Color.White, fontSize = 24.sp, textAlign = TextAlign.Center)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(24.dp))
                if (alarme.desafio) {
                    Desafio(onAcertou = onDesligar)
                } else {
                    Button(
                        onClick = onDesligar,
                        modifier = Modifier.fillMaxWidth().height(76.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB71C1C)),
                    ) { Text("DESLIGAR", fontSize = 26.sp, fontWeight = FontWeight.Black) }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onAdiar,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) { Text("Adiar ${Agendador.SONECA_MINUTOS} min", fontSize = 18.sp) }
            }
        }
    }
}

/** Conta de somar que precisa ser resolvida pra desligar: acorda o cérebro de verdade. */
@Composable
private fun Desafio(onAcertou: () -> Unit) {
    val a = remember { Random.nextInt(12, 60) }
    val b = remember { Random.nextInt(12, 60) }
    var resposta by remember { mutableStateOf("") }
    var errou by remember { mutableStateOf(false) }
    val corErro = Color(0xFFFFCDD2)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Pra desligar, resolva:", color = Color.White, fontSize = 18.sp)
        Text(
            "$a + $b = ${resposta.ifEmpty { "?" }}",
            color = if (errou) corErro else Color.White,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(if (errou) "Errou! Tenta de novo." else "", color = corErro, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        Teclado { tecla ->
            when (tecla) {
                "⌫" -> {
                    resposta = resposta.dropLast(1)
                }
                "OK" -> {
                    if (resposta.toIntOrNull() == a + b) {
                        onAcertou()
                    } else {
                        errou = true
                        resposta = ""
                    }
                }
                else -> {
                    if (resposta.length < 3) {
                        resposta += tecla
                        errou = false
                    }
                }
            }
        }
    }
}

/** Teclado numérico próprio: funciona por cima da tela de bloqueio, sem depender do teclado do sistema. */
@Composable
private fun Teclado(onTecla: (String) -> Unit) {
    val teclas = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        teclas.chunked(3).forEach { linha ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                linha.forEach { tecla ->
                    FilledTonalButton(
                        onClick = { onTecla(tecla) },
                        modifier = Modifier.size(width = 84.dp, height = 60.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(tecla, fontSize = 22.sp) }
                }
            }
        }
    }
}
