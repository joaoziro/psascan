package br.psascan.painel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.psascan.painel.Status

data class Aparelho(val nome: String, val endereco: String)

/** Um tema instalado, para a lista da seção "Tema". */
data class ItemTema(val id: String, val nome: String, val descricao: String, val autor: String, val embutido: Boolean)

/** Temas instalados, o ativo e a última mensagem (motivo da recusa de uma importação, por exemplo). */
data class EstadoTema(val lista: List<ItemTema> = emptyList(), val ativoId: String = "", val mensagem: String = "",
    val erro: Boolean = false)

/** Escolha do scanner (aparelhos já pareados) ou do simulador, e o log da conexão. */
@Composable
fun ScannerTela(
    pareados: List<Aparelho>?, escolhido: String?, st: Status, log: List<String>,
    tema: EstadoTema, aoAtivarTema: (String) -> Unit, aoImportarTema: () -> Unit, aoExportarTema: () -> Unit,
    aoEscolher: (String) -> Unit, aoPermitir: () -> Unit, aoVoltar: () -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Selo("‹ PAINEL", Cor.grande, aoVoltar)
                Text("Scanner", color = Cor.grande, fontSize = 18.sp, fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(start = 16.dp))
            }
            Text("Pareie o PSA COM nas configurações de Bluetooth do Android (PIN 1234) e escolha aqui.",
                color = Cor.apagado, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Opcao("Simulador", "sem carro: repete um passeio de 2 min", escolhido == SIM) { aoEscolher(SIM) } }
                if (pareados == null) item {
                    Opcao("Permitir Bluetooth", "o Android precisa da sua permissão para listar o scanner", false, aoPermitir)
                } else items(pareados) { a ->
                    Opcao(a.nome, a.endereco, escolhido == a.endereco) { aoEscolher(a.endereco) }
                }
                item {
                    Text("Tema", color = Cor.grande, fontSize = 16.sp, fontWeight = FontWeight.Light,
                        modifier = Modifier.padding(top = 10.dp))
                }
                items(tema.lista, key = { "tema-" + it.id }) { t ->
                    val sub = listOf(t.descricao, if (t.autor.isNotEmpty()) "por ${t.autor}" else "")
                        .filter { it.isNotEmpty() }.joinToString(" · ")
                    Opcao(t.nome, sub, t.id == tema.ativoId) { aoAtivarTema(t.id) }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Selo("IMPORTAR TEMA", Cor.verde, aoImportarTema)
                        Selo("EXPORTAR TEMA", Cor.verde, aoExportarTema)
                    }
                }
                if (tema.mensagem.isNotEmpty()) item {
                    Text(tema.mensagem, color = if (tema.erro) Cor.vermelho else Cor.apagado, fontSize = 12.sp)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 20.dp)) {
            Text("Conexão: ${st.link.name.lowercase()}" + (if (st.etapa.isNotEmpty()) " · ${st.etapa}" else "") +
                (if (st.reconexoes > 0) " · ${st.reconexoes} reconexões" else ""),
                color = Cor.medio, fontSize = 13.sp)
            if (st.erro.isNotEmpty()) Text(st.erro, color = Cor.vermelho, fontSize = 12.sp)
            val estado = rememberLazyListState()
            LaunchedEffect(log.size) { if (log.isNotEmpty()) estado.scrollToItem(log.size - 1) }
            LazyColumn(Modifier.padding(top = 8.dp).fillMaxWidth().weight(1f)
                .background(Cor.campo, RoundedCornerShape(4.dp)).padding(8.dp), state = estado) {
                items(log) { Text(it, color = Cor.fino, fontSize = 10.sp, fontFamily = FONTE_MONO) }
            }
        }
    }
}

const val SIM = "simulador"

@Composable
private fun Opcao(titulo: String, sub: String, marcado: Boolean, aoTocar: () -> Unit) {
    val borda = if (marcado) Cor.passou else Cor.borda
    val forma = RoundedCornerShape(Temas.painel.raios.cartao.dp)
    Row(Modifier.fillMaxWidth().border(1.dp, borda, forma).clickable(onClick = aoTocar)
        .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, color = Cor.grande, fontSize = 16.sp)
            Text(sub, color = Cor.apagado, fontSize = 12.sp)
        }
        if (marcado) { Spacer(Modifier.padding(4.dp)); Selo("EM USO", Cor.passou, tam = 10) }
    }
}
