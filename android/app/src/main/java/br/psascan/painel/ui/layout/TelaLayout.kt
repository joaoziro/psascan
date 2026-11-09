package br.psascan.painel.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.psascan.painel.ItemLayout
import br.psascan.painel.ui.Cor
import br.psascan.painel.ui.Selo

/** O que a tela mostra dos layouts: a lista, o ativo e a última mensagem (motivo de uma recusa, por exemplo). */
data class EstadoLayouts(
    val lista: List<ItemLayout> = emptyList(), val ativo: Layout? = null, val mensagem: String = "", val erro: Boolean = false,
)

/**
 * O modo PAINEL do app: o layout ativo montado pela [Grade], com o seletor de layout, EDITAR e, num menu, importar,
 * exportar e apagar. No modo edição ([edicao] != null) a grade fica visível por cima dos widgets, com o painel de
 * opções ao lado, "+ widget", Cancelar e "Salvar como…". O estado da edição fica fora (no AppModel), para
 * sobreviver à troca de modo do app.
 */
@Composable
fun TelaPersonalizavel(
    estado: EstadoLayouts, fonte: FonteDados, edicao: EstadoEdicao?,
    aoAtivar: (String) -> Unit, aoImportar: () -> Unit, aoExportar: (String) -> Unit, aoApagar: (String) -> Unit,
    aoEdicao: (EstadoEdicao?) -> Unit, aoSalvar: (Layout) -> String?, aoAviso: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ativo = estado.ativo ?: return
    val layout = edicao?.atual ?: ativo
    val metricas = fonte.perfil.metricas
    var salvando by remember { mutableStateOf(false) }
    var apagando by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (edicao == null) {
                Seletor(estado, ativo, aoAtivar)
                Selo("EDITAR", Cor.passou, { aoEdicao(EstadoEdicao(ativo, ativo)) })
                val item = estado.lista.firstOrNull { it.id == ativo.id }
                Mais(item, { aoImportar() }, { aoExportar(ativo.id) }, { apagando = true })
            } else {
                Selo("+ WIDGET", Cor.verde, {
                    EditorLayout.adicionar(edicao.atual, fonte.catalogo, metricas)
                        ?.let { n -> aoEdicao(edicao.copy(atual = n, selecionado = n.widgets.last().id)) }
                        ?: aoAviso("Não há espaço livre na grade. Apague ou encolha um widget primeiro.")
                })
                Text("editando: ${edicao.atual.nome}", color = Cor.apagado, fontSize = 12.sp, maxLines = 1)
                Selo("CANCELAR", Cor.fino, { aoEdicao(null) })
                Selo("SALVAR COMO…", Cor.passou, { salvando = true }, marcado = true)
            }
            val msg = estado.mensagem.ifEmpty {
                if (ativo.orientacao == "retrato") "Este layout foi pensado para retrato." else ""
            }
            if (msg.isNotEmpty()) Text(msg, color = if (estado.erro) Cor.vermelho else Cor.apagado, fontSize = 12.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Grade(layout, fonte, Modifier.fillMaxSize())
                if (edicao != null) EdicaoGrade(layout, fonte.catalogo, edicao.selecionado,
                    layout.widgets.filter { motivoIndisponivel(it, fonte) != null }.map { it.id }.toSet(),
                    aoSelecionar = { aoEdicao(edicao.copy(selecionado = it)) },
                    aoGeometria = { id, r -> aoEdicao(edicao.copy(atual = EditorLayout.geometria(edicao.atual, id, r))) },
                    modifier = Modifier.fillMaxSize())
            }
            if (edicao != null) PainelWidget(layout, fonte.catalogo, metricas, edicao.selecionado,
                aoMudar = { aoEdicao(edicao.copy(atual = it)) }, aoAviso = aoAviso,
                aoFechar = { aoEdicao(edicao.copy(selecionado = null)) },
                modifier = Modifier.width(290.dp).fillMaxHeight().padding(start = 8.dp))
        }
    }
    if (salvando && edicao != null) DialogoSalvar(edicao.atual, estado.lista, aoSalvar = { l -> aoSalvar(l).also { r -> if (r == null) salvando = false } },
        aoCancelar = { salvando = false })
    if (apagando) AlertDialog(
        onDismissRequest = { apagando = false },
        containerColor = Cor.superficie,
        title = { Text("Apagar o layout?", color = Cor.grande) },
        text = { Text("\"${ativo.nome}\" será apagado do aparelho e o Painel padrão volta a ser o ativo. Isso não pode ser desfeito.",
            color = Cor.medio, fontSize = 14.sp) },
        confirmButton = { TextButton({ apagando = false; aoApagar(ativo.id) }) { Text("APAGAR", color = Cor.vermelho) } },
        dismissButton = { TextButton({ apagando = false }) { Text("Cancelar", color = Cor.grande) } },
    )
}

@Composable
private fun Seletor(estado: EstadoLayouts, ativo: Layout, aoAtivar: (String) -> Unit) {
    var aberto by remember { mutableStateOf(false) }
    Box {
        Selo(ativo.nome.uppercase() + " ▾", Cor.grande, { aberto = true })
        DropdownMenu(aberto, { aberto = false }, containerColor = Cor.superficie2) {
            // inválidos ficam de fora (o motivo aparece ao tentar ativar um que sumiu)
            for (l in estado.lista.filter { it.valido || it.id == ativo.id }) DropdownMenuItem(
                text = {
                    Column {
                        Text(l.nome + if (l.embutido) "" else " (meu)", color = if (l.id == ativo.id) Cor.passou else Cor.grande, fontSize = 14.sp)
                        if (l.descricao.isNotEmpty()) Text(l.descricao, color = Cor.apagado, fontSize = 11.sp, maxLines = 2,
                            modifier = Modifier.width(260.dp))
                    }
                },
                onClick = { aberto = false; if (l.id != ativo.id) aoAtivar(l.id) })
        }
    }
}

@Composable
private fun Mais(item: ItemLayout?, aoImportar: () -> Unit, aoExportar: () -> Unit, aoApagar: () -> Unit) {
    var aberto by remember { mutableStateOf(false) }
    Box {
        Selo("MAIS ▾", Cor.fino, { aberto = true })
        DropdownMenu(aberto, { aberto = false }, containerColor = Cor.superficie2) {
            DropdownMenuItem({ Text("Importar layout…", color = Cor.grande, fontSize = 14.sp) }, { aberto = false; aoImportar() })
            DropdownMenuItem({ Text("Exportar este layout", color = Cor.grande, fontSize = 14.sp) }, { aberto = false; aoExportar() })
            if (item != null && !item.embutido)
                DropdownMenuItem({ Text("Apagar este layout", color = Cor.vermelho, fontSize = 14.sp) }, { aberto = false; aoApagar() })
        }
    }
}

/** Id e nome do layout novo. Os que vêm com o app não mudam: a edição vira uma cópia sua. */
@Composable
private fun DialogoSalvar(atual: Layout, lista: List<ItemLayout>, aoSalvar: (Layout) -> String?, aoCancelar: () -> Unit) {
    val meu = lista.any { it.id == atual.id && !it.embutido }
    var id by remember { mutableStateOf(if (meu) atual.id else (atual.id + "-meu").take(32)) }
    var nome by remember { mutableStateOf(if (meu) atual.nome else (atual.nome + " (meu)").take(40)) }
    var erro by remember { mutableStateOf("") }
    var substituir by remember { mutableStateOf(false) }
    fun salvar() {
        val lid = id.trim()
        when {
            !LimitesLayout.RE_ID.matches(lid) -> erro = "Id inválido: use a-z, 0-9 e hífen, de 2 a 32 caracteres."
            lista.any { it.id == lid && it.embutido } -> erro = "\"$lid\" é um layout que vem com o app: escolha outro id."
            nome.isBlank() -> erro = "Dê um nome ao layout."
            lista.any { it.id == lid } && lid != atual.id && !substituir -> {
                erro = "Já existe um layout \"$lid\". Toque em SALVAR de novo para substituir."; substituir = true
            }
            else -> aoSalvar(atual.copy(id = lid, nome = nome.trim()))?.let { erro = "Não foi possível salvar: $it" }
        }
    }
    AlertDialog(
        onDismissRequest = aoCancelar,
        containerColor = Cor.superficie,
        title = { Text("Salvar layout", color = Cor.grande) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Os layouts que vêm com o app não mudam: a edição vira uma cópia sua.", color = Cor.medio, fontSize = 13.sp)
                OutlinedTextField(id, { id = it.lowercase().take(32); substituir = false }, singleLine = true,
                    label = { Text("Id (a-z, 0-9, hífen)", fontSize = 12.sp) }, textStyle = TextStyle(fontSize = 14.sp),
                    colors = corCampos, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(nome, { nome = it.take(40) }, singleLine = true,
                    label = { Text("Nome", fontSize = 12.sp) }, textStyle = TextStyle(fontSize = 14.sp),
                    colors = corCampos, modifier = Modifier.fillMaxWidth())
                if (erro.isNotEmpty()) Text(erro, color = Cor.vermelho, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(::salvar) { Text("SALVAR", color = Cor.passou) } },
        dismissButton = { TextButton(aoCancelar) { Text("Cancelar", color = Cor.grande) } },
    )
}
