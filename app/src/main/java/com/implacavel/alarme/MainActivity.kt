package com.implacavel.alarme

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/** Tela principal: permissões, música, botão de teste e lista de alarmes. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TemaImplacavel { TelaPrincipal() } }
    }
}

@Composable
private fun TelaPrincipal() {
    val ctx = LocalContext.current
    val alarmes by Alarmes.lista.collectAsStateWithLifecycle()
    val tocando by AlarmeService.tocando.collectAsStateWithLifecycle()
    var editando by remember { mutableStateOf<Alarme?>(null) }

    // Relógio da tela, usado nos textos "toca em 9 h 12 min"
    var agora by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            agora = ZonedDateTime.now()
        }
    }

    // Permissões: relidas toda vez que a tela volta (ex.: depois de liberar algo nas Configurações)
    var poderes by remember { mutableStateOf(lerPoderes(ctx)) }
    LifecycleResumeEffect(Unit) {
        poderes = lerPoderes(ctx)
        onPauseOrDispose { }
    }

    // Permissões com diálogo do sistema (notificação, microfone, câmera). Se o usuário já negou, o
    // sistema não pergunta de novo; aí, se o pedido veio de um botão "Liberar", abre as Configurações.
    var pedidoDoBotao by remember { mutableStateOf<Poder?>(null) }
    val pedirPermissao = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        pedidoDoBotao?.let { if (!ok) abrirConfiguracao(ctx, it) }
        pedidoDoBotao = null
        poderes = lerPoderes(ctx)
    }
    LaunchedEffect(Unit) {
        Poder.NOTIFICACOES.permissao?.takeUnless { Poderes.concedida(ctx, it) }?.let { pedirPermissao.launch(it) }
    }
    val liberar: (Poder) -> Unit = { poder ->
        val permissao = poder.permissao
        if (permissao != null && !Poderes.concedida(ctx, permissao)) {
            pedidoDoBotao = poder
            pedirPermissao.launch(permissao)
        } else {
            abrirConfiguracao(ctx, poder)
        }
    }

    val musica by Ajustes.musica.collectAsStateWithLifecycle()
    val escolherMusica = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Ajustes.escolherMusica(ctx, uri)
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editando = Alarme(id = Alarmes.novoId(), hora = 7, minuto = 0) },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text("Novo alarme") },
            )
        },
    ) { margens ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = margens.calculateTopPadding() + 8.dp,
                bottom = margens.calculateBottomPadding() + 96.dp, // espaço pro botão flutuante
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Cabecalho(alarmes, agora) }
            // Atalho de volta pra tela do alarme, caso a notificação tenha sumido
            tocando?.let { alarme ->
                item {
                    Button(
                        onClick = { ctx.startActivity(Intent(ctx, AlarmeActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text("⏰ ${alarme.rotulo.ifBlank { "Alarme" }} tocando · abrir") }
                }
            }
            item { CartaoPoderes(poderes, liberar) }
            item {
                CartaoMusica(
                    nome = musica?.nome,
                    onEscolher = { escolherMusica.launch(arrayOf("audio/*")) },
                    onTirar = { Ajustes.tirarMusica(ctx) },
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        Agendador.agendarTeste(ctx, 10)
                        Toast.makeText(ctx, "Toca em 10 segundos. Bloqueie a tela pra ver o efeito completo.", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Testar agora (toca em 10 s)") }
            }
            items(alarmes, key = { it.id }) { alarme ->
                CartaoAlarme(
                    alarme = alarme,
                    agora = agora,
                    onLigar = { ligado ->
                        Agendador.cancelarSoneca(ctx, alarme.id)
                        salvarEAgendar(ctx, alarme.copy(ativo = ligado, sonecaAte = null))
                    },
                    onCancelarSoneca = { Agendador.cancelarSoneca(ctx, alarme.id) },
                    onClick = { editando = alarme },
                )
            }
            if (alarmes.isEmpty()) {
                item {
                    Text(
                        "Nenhum alarme ainda. Toque em \"Novo alarme\" pra criar o primeiro.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
    }

    editando?.let { alarme ->
        EditorAlarme(
            inicial = alarme,
            novo = Alarmes.buscar(alarme.id) == null,
            onSalvar = {
                salvarEAgendar(ctx, it)
                editando = null
            },
            onExcluir = {
                Agendador.cancelar(ctx, alarme.id)
                Alarmes.remover(alarme.id)
                editando = null
            },
            onCancelar = { editando = null },
        )
    }
}

private fun lerPoderes(ctx: Context) = Poder.entries.associateWith { Poderes.liberado(ctx, it) }

private fun abrirConfiguracao(ctx: Context, poder: Poder) {
    runCatching { ctx.startActivity(Poderes.telaParaLiberar(ctx, poder)) }.onFailure {
        // Nem todo celular tem a tela específica; aí abre as informações do app
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri()))
    }
}

private fun salvarEAgendar(ctx: Context, alarme: Alarme) {
    Alarmes.salvar(alarme)
    val quando = Agendador.agendar(ctx, alarme) ?: return
    Toast.makeText(ctx, "Alarme ${tempoAte(ZonedDateTime.now(), quando)}", Toast.LENGTH_SHORT).show()
}
