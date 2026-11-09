package br.psascan.painel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.psascan.painel.ArquivoGravado
import br.psascan.painel.EstadoGravacao
import br.psascan.painel.ModoLeitura
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tempo desde o início, "12:34" ou "1:02:34". */
fun duracao(inicioMs: Long, agoraMs: Long): String {
    val s = maxOf(0L, (agoraMs - inicioMs) / 1000)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Relógio que redesenha a cada segundo (para o tempo de gravação). */
@Composable
fun agoraMs(): Long {
    var t by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); t = System.currentTimeMillis() } }
    return t
}

@Composable
fun GravacoesTela(
    g: EstadoGravacao, lista: List<ArquivoGravado>, onde: String,
    aoModo: (ModoLeitura) -> Unit, aoCompartilhar: (ArquivoGravado) -> Unit, aoAtualizar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(g.ativa) { aoAtualizar() }   // ao abrir e quando uma gravação termina
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gravações", color = Cor.grande, fontSize = 18.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.weight(1f))
            Text("Leitura", color = Cor.apagado, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
            for (m in ModoLeitura.entries) {
                Selo(m.texto.uppercase(), if (m == g.modo) Cor.passou else Cor.fino, { aoModo(m) }, marcado = m == g.modo)
                Spacer(Modifier.padding(4.dp))
            }
        }
        val explica = when (g.modo) {
            ModoLeitura.NORMAL -> "Normal: todos os parâmetros. Lambda, RPM, MAP e borboleta a ~4 leituras/s; " +
                "injeção, motor de passo e o resto do bloco 1 a ~2/s."
            ModoLeitura.FOCO -> "Foco lambda: só o bloco da lambda (RPM, MAP, borboleta, temperaturas…) na taxa " +
                "máxima, ~6/s. Injeção e motor de passo ficam de fora: estão no outro bloco da central."
        }
        Text(explica, color = Cor.apagado, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        val aviso = g.aviso.ifEmpty { "Os arquivos ficam em $onde." }
        Text(aviso, color = if (g.aviso.startsWith("Salvo")) Cor.verde else if (g.aviso.isEmpty()) Cor.apagado else Cor.passou,
            fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        if (lista.isEmpty()) Text("Nenhuma gravação ainda. Toque em ● GRAVAR embaixo.", color = Cor.medio, fontSize = 14.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lista, key = { it.uri.toString() }) { a -> Linha(a, g.ativa && a.nome == g.arquivo, aoCompartilhar) }
        }
    }
}

@Composable
private fun Linha(a: ArquivoGravado, emCurso: Boolean, aoCompartilhar: (ArquivoGravado) -> Unit) {
    val data = remember(a.modificadoMs) { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(a.modificadoMs)) }
    val forma = RoundedCornerShape(Temas.painel.raios.cartao.dp)
    Row(Modifier.fillMaxWidth().background(Cor.superficie2, forma)
        .border(1.dp, Cor.borda, forma).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(a.nome, color = Cor.grande, fontSize = 15.sp)
            val tam = if (a.tamanho > 0) " · " + tamanho(a.tamanho) else ""
            Text(if (emCurso) "gravando agora" else "$data$tam", color = if (emCurso) Cor.vermelho else Cor.apagado, fontSize = 12.sp)
        }
        if (!emCurso) Selo("COMPARTILHAR", Cor.verde, { aoCompartilhar(a) })
    }
}

private fun tamanho(b: Long) = when {
    b >= 1_000_000 -> "%.1f MB".format(b / 1e6).replace('.', ',')
    b >= 1000 -> "${b / 1000} kB"
    else -> "$b B"
}

/** Notas rápidas para não precisar digitar dirigindo. */
private val NOTAS = listOf("acelerei", "parei no farol", "marcha lenta", "subida", "falhou")

/** O instante já foi guardado no toque em MARCAR; aqui só a nota. Cancelar descarta a marca. */
@Composable
fun DialogoMarcar(numero: Int, aoMarcar: (String) -> Unit, aoCancelar: () -> Unit) {
    var nota by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = aoCancelar,
        containerColor = Cor.superficie,
        title = { Text("Marca $numero", color = Cor.grande) },
        text = {
            Column {
                Text("A hora já foi guardada. Nota (opcional):", color = Cor.medio, fontSize = 13.sp)
                OutlinedTextField(nota, { nota = it.take(80) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { aoMarcar(nota) }),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Cor.grande, unfocusedTextColor = Cor.grande,
                        focusedBorderColor = Cor.passou, cursorColor = Cor.passou),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in NOTAS) Selo(n, Cor.fino, { aoMarcar(n) })
                }
            }
        },
        confirmButton = { TextButton({ aoMarcar(nota) }) { Text("MARCAR", color = Cor.passou) } },
        dismissButton = { TextButton(aoCancelar) { Text("Cancelar", color = Cor.grande) } },
    )
}

@Composable
fun DialogoParar(g: EstadoGravacao, aoParar: () -> Unit, aoCancelar: () -> Unit) {
    AlertDialog(
        onDismissRequest = aoCancelar,
        containerColor = Cor.superficie,
        title = { Text("Parar a gravação?", color = Cor.grande) },
        text = {
            Text("${g.arquivo}: ${duracao(g.inicioMs, System.currentTimeMillis())}, ${g.linhas} linhas, " +
                "${g.marcas} marca(s), ${g.quedas} queda(s). Antes de fechar o app lê as falhas da central " +
                "(até 15 s). Para gravar de novo começa um arquivo novo.", color = Cor.medio, fontSize = 14.sp)
        },
        confirmButton = { TextButton(aoParar) { Text("PARAR", color = Cor.vermelho) } },
        dismissButton = { TextButton(aoCancelar) { Text("Continuar gravando", color = Cor.grande) } },
    )
}
