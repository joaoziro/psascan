package br.psascan.painel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.psascan.painel.FalhaLida

/** Estado da tela: null = ainda não leu nesta abertura do app. */
data class EstadoFalhas(
    val lista: List<FalhaLida>? = null, val ocupado: String = "", val erro: String = "",
    val hora: String = "", val apagadas: Boolean = false,
    /** Chave "Permitir comandos de escrita": desligada sempre que o app abre (não é guardada). */
    val escrita: Boolean = false,
)

@Composable
fun FalhasTela(
    e: EstadoFalhas, bytesApagar: String?,
    aoLer: () -> Unit, aoApagar: () -> Unit, aoEscrita: (Boolean) -> Unit, modifier: Modifier = Modifier,
) {
    var confirmar by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Falhas da central", color = Cor.grande, fontSize = 18.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.weight(1f))
            if (e.ocupado.isEmpty()) {
                Selo("LER FALHAS", Cor.verde, aoLer)
                Spacer(Modifier.padding(6.dp))
                if (e.escrita && bytesApagar != null) Selo("APAGAR", Cor.vermelho, { confirmar = true })
                else Selo("APAGAR", Cor.apagado)
            } else {
                Text(e.ocupado, color = Cor.passou, fontSize = 13.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Switch(e.escrita, aoEscrita, enabled = bytesApagar != null, colors = SwitchDefaults.colors(
                checkedThumbColor = Cor.vermelho, checkedTrackColor = Temas.painel.cores.chaveLigadaTrilho, checkedBorderColor = Cor.vermelho,
                uncheckedThumbColor = Cor.fino, uncheckedTrackColor = Cor.superficie, uncheckedBorderColor = Cor.apagado))
            Column(Modifier.padding(start = 12.dp)) {
                Text("Permitir comandos de escrita", color = if (e.escrita) Cor.vermelho else Cor.medio, fontSize = 13.sp)
                Text(when {
                    bytesApagar == null -> "Este perfil não tem comando validado para apagar falhas."
                    e.escrita -> "Ligada: o APAGAR funciona, depois de confirmar. Desliga sozinha quando o app fecha."
                    else -> "Somente leitura: o APAGAR fica bloqueado até ligar esta chave."
                }, color = Cor.apagado, fontSize = 11.sp)
            }
        }
        val sub = when {
            e.erro.isNotEmpty() -> e.erro
            e.lista == null -> "Toque em LER FALHAS. A ignição precisa estar ligada."
            e.apagadas && e.lista.isEmpty() -> "Falhas apagadas e conferidas às ${e.hora}: a central não tem nenhuma."
            e.lista.isEmpty() -> "Nenhuma falha na central (lido às ${e.hora})."
            else -> "${e.lista.size} falha(s), lidas às ${e.hora}. " +
                if (e.apagadas) "Depois de apagar, estas voltaram: o defeito ainda está lá." else ""
        }
        Text(sub, color = if (e.erro.isNotEmpty()) Cor.vermelho else Cor.apagado, fontSize = 13.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(e.lista ?: emptyList()) { Cartao(it) }
        }
    }
    if (confirmar && bytesApagar != null) {
        AlertDialog(
            onDismissRequest = { confirmar = false },
            containerColor = Cor.superficie,
            title = { Text("Apagar as falhas?", color = Cor.grande) },
            text = {
                Column {
                    Text("A central esquece os códigos memorizados e a luz da injeção apaga. Se o defeito " +
                        "continuar, a falha volta. Depois de apagar o app lê de novo para conferir.",
                        color = Cor.medio, fontSize = 14.sp)
                    Text("Envia ao scanner: $bytesApagar", color = Cor.fino, fontSize = 12.sp,
                        fontFamily = FONTE_MONO, modifier = Modifier.padding(top = 10.dp))
                }
            },
            confirmButton = {
                TextButton({ confirmar = false; aoApagar() }) { Text("APAGAR", color = Cor.vermelho) }
            },
            dismissButton = { TextButton({ confirmar = false }) { Text("Cancelar", color = Cor.grande) } },
        )
    }
}

@Composable
private fun Cartao(f: FalhaLida) {
    val cor = if (f.presente) Cor.vermelho else Cor.passou
    val forma = RoundedCornerShape(Temas.painel.raios.cartao.dp)
    Column(Modifier.fillMaxWidth().background(Cor.superficie2, forma)
        .border(1.dp, Cor.borda, forma).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.hex, color = cor, fontSize = 15.sp, fontFamily = FONTE_MONO)
            Text(f.nome, color = Cor.grande, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp).weight(1f))
            Selo(if (f.presente) "PRESENTE" else "MEMORIZADA", cor, tam = 10)
        }
        Text(f.tipoNome, color = Cor.medio, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        if (f.causas.isNotEmpty()) {
            Text("Causas comuns", color = Cor.apagado, fontSize = 11.sp, letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 8.dp))
            for (c in f.causas) Text("· $c", color = Cor.medio, fontSize = 13.sp)
        }
    }
}
