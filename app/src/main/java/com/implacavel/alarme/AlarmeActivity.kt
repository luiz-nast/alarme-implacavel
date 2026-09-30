package com.implacavel.alarme

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.LocalTime

/**
 * Tela do alarme tocando. Aberta pela notificação em tela cheia do [AlarmeService]; aparece por
 * cima da tela de bloqueio, acende a tela e fecha sozinha quando o alarme para.
 * Com a missão ligada, mostra as etapas de Missao.kt; sem ela, só o botão DESLIGAR.
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
                alarme?.let { TelaAlarme(it) }
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
private fun TelaAlarme(alarme: Alarme) {
    val ctx = LocalContext.current
    // Saveable: se a tela for recriada, a missão continua na mesma etapa (a música já pode estar calada)
    var etapa by rememberSaveable { mutableStateOf(Etapa.FALAR) }

    // Na etapa da câmera a tela fica clara e no brilho máximo, pra iluminar o rosto no escuro
    val claro = alarme.missao && etapa == Etapa.OLHAR
    BrilhoMaximo(claro)
    val corTexto = if (claro) Color(0xFF3B0000) else Color.White
    val fundo = if (claro) {
        listOf(Color(0xFFFFFBF2), Color(0xFFFFE0B2))
    } else {
        listOf(Color(0xFFB71C1C), Color(0xFF3B0000), Color.Black)
    }

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
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(fundo))) {
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
                Spacer(Modifier.height(16.dp))
                Icon(
                    painterResource(R.drawable.ic_alarme),
                    contentDescription = null,
                    tint = corTexto,
                    modifier = Modifier.size(56.dp).scale(escala),
                )
                Text(hhmm(agora), color = corTexto, fontSize = 72.sp, fontWeight = FontWeight.Bold)
                Text(alarme.rotulo.ifBlank { "Alarme" }, color = corTexto, fontSize = 22.sp, textAlign = TextAlign.Center)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                when {
                    !alarme.missao -> BotaoGrande("DESLIGAR", { AlarmeService.parar(ctx) })
                    etapa == Etapa.FALAR -> EtapaFalar(
                        onStop = {
                            AlarmeService.silenciar(ctx)
                            etapa = Etapa.OLHAR
                        },
                    )
                    else -> EtapaOlhar(
                        onConcluiu = { AlarmeService.parar(ctx) },
                        onDesistiu = {
                            AlarmeService.retomar(ctx)
                            etapa = Etapa.FALAR
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { AlarmeService.adiar(ctx) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    border = BorderStroke(1.dp, corTexto.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = corTexto),
                ) { Text("Adiar ${Agendador.SONECA_MINUTOS} min", fontSize = 18.sp) }
            }
        }
    }
}

/** Botão grande das telas do alarme: branco no fundo vermelho, vermelho no fundo claro. */
@Composable
fun BotaoGrande(texto: String, onClick: () -> Unit, fundoClaro: Boolean = false) {
    val cores = if (fundoClaro) {
        ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C), contentColor = Color.White)
    } else {
        ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB71C1C))
    }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(76.dp),
        shape = RoundedCornerShape(24.dp),
        colors = cores,
    ) { Text(texto, fontSize = 24.sp, fontWeight = FontWeight.Black) }
}

/** Força o brilho máximo da tela enquanto [ligado]; ao sair, devolve o brilho do sistema. */
@Composable
private fun BrilhoMaximo(ligado: Boolean) {
    val janela = LocalActivity.current?.window ?: return
    DisposableEffect(ligado) {
        val original = janela.attributes.screenBrightness
        if (ligado) janela.attributes = janela.attributes.apply { screenBrightness = 1f }
        onDispose { janela.attributes = janela.attributes.apply { screenBrightness = original } }
    }
}
