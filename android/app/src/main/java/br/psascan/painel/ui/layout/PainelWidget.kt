package br.psascan.painel.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.psascan.painel.protocolo.Metrica
import br.psascan.painel.ui.Cor
import br.psascan.painel.ui.Selo
import br.psascan.painel.ui.Temas
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * O painel lateral do modo edição: com um widget escolhido, troca tipo, métrica (só as que o tipo aceita), título e
 * opções (o formulário é gerado do catálogo); sem nenhum, o tamanho da grade. Cada mudança devolve o layout novo por [aoMudar].
 */
@Composable
fun PainelWidget(
    layout: Layout, catalogo: Catalogo, metricas: List<Metrica>, selecionado: String?,
    aoMudar: (Layout) -> Unit, aoAviso: (String) -> Unit, aoFechar: () -> Unit, modifier: Modifier = Modifier,
) {
    val w = layout.widgets.firstOrNull { it.id == selecionado }
    Column(modifier.verticalScroll(rememberScrollState()).background(Cor.superficie, RoundedCornerShape(Temas.painel.raios.cartao.dp))
        .border(1.dp, Cor.borda, RoundedCornerShape(Temas.painel.raios.cartao.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (w == null) return@Column OpcoesDaGrade(layout, aoMudar, aoAviso)
        val tipo = catalogo.tipos.getValue(w.tipo)
        val aceitas = EditorLayout.metricasAceitas(catalogo, w.tipo, metricas)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Widget ${w.id}", color = Cor.grande, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Selo("FECHAR", Cor.fino, aoFechar, tam = 10)
        }
        Menu("Tipo", tipo.nome, catalogo.tipos.map { (k, t) -> k to t.nome }) { novo ->
            if (novo != w.tipo) EditorLayout.trocarTipo(layout, catalogo, w.id, novo, metricas)?.let(aoMudar)
                ?: aoAviso("Não cabe aqui como \"${catalogo.tipos[novo]?.nome}\" (tamanho mínimo ${catalogo.tipos[novo]?.minimo?.joinToString("×")}).")
        }
        if (tipo.usaMetrica && !tipo.dispensaMetrica(w.opcoes)) {
            val opcoes = aceitas.map { it.id to it.nome + if (it.unidade.isNotEmpty()) " (${it.unidade})" else "" }
            val atual = metricas.firstOrNull { it.id == w.metrica }
            Menu("Métrica", atual?.nome ?: ((w.metrica ?: "—") + " (não serve)"), opcoes) { aoMudar(EditorLayout.metrica(layout, w.id, it)) }
        }
        val padrao = metricas.firstOrNull { it.id == w.metrica }?.curto
        Texto("Título", w.titulo.orEmpty(), padrao ?: "(sem título)", 40, "titulo-${w.id}") { aoMudar(EditorLayout.titulo(layout, w.id, it)) }
        Text("Tamanho ${w.largura}×${w.altura} (aceito: ${tipo.minimo.joinToString("×")} a ${tipo.maximo.joinToString("×")}).",
            color = Cor.apagado, fontSize = 12.sp)
        if (tipo.opcoes.isNotEmpty()) {
            Text("Opções", color = Cor.medio, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            for ((k, esp) in tipo.opcoes) Campo(w, listOf(k), esp, k.replace('_', ' '), layout, catalogo, metricas, aoMudar)
        }
        Selo("REMOVER WIDGET", Cor.vermelho, { aoMudar(EditorLayout.remover(layout, w.id)); aoFechar() }, tam = 11)
    }
}

@Composable
private fun OpcoesDaGrade(layout: Layout, aoMudar: (Layout) -> Unit, aoAviso: (String) -> Unit) {
    Text("Grade", color = Cor.grande, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Text("Toque num widget para editar. Arraste pelo corpo para mover e pela alça do canto para redimensionar.",
        color = Cor.apagado, fontSize = 12.sp)
    for ((nome, colunas) in listOf("Colunas" to true, "Linhas" to false)) {
        val v = if (colunas) layout.grade.colunas else layout.grade.linhas
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(nome, color = Cor.medio, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Selo("−", Cor.fino, { EditorLayout.tamanhoGrade(layout, colunas, v - 1)?.let(aoMudar)
                ?: aoAviso("Não dá para diminuir: há widgets além desse limite (mova ou apague antes) ou a grade já está no mínimo.") })
            Text("$v", color = Cor.grande, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp))
            Selo("+", Cor.fino, { EditorLayout.tamanhoGrade(layout, colunas, v + 1)?.let(aoMudar) ?: aoAviso("A grade já está no tamanho máximo.") })
        }
    }
    Text("${layout.widgets.size} widget(s) de no máximo ${LimitesLayout.MAX_WIDGETS}.", color = Cor.apagado, fontSize = 12.sp)
}

// ---------------------------------------------------------------------------------------------
// Campos do formulário de opções (gerados da descrição do catálogo)

@Composable
private fun Campo(
    w: WidgetLayout, caminho: List<String>, esp: EspOpcao, rotulo: String, layout: Layout, catalogo: Catalogo,
    metricas: List<Metrica>, aoMudar: (Layout) -> Unit,
) {
    val atual = EditorLayout.valorOpcao(w.opcoes, caminho)
    val definir = { v: JsonElement? -> aoMudar(EditorLayout.opcao(layout, w.id, caminho, v, catalogo, metricas)) }
    val chave = w.id + "/" + caminho.joinToString(".")
    when (esp.tipo) {
        "bool" -> Chave(rotulo, (atual as? JsonPrimitive)?.booleanOrNull ?: (esp.padrao as? JsonPrimitive)?.booleanOrNull ?: false) {
            definir(JsonPrimitive(it))
        }
        "lista" -> {
            val atualTexto = (atual as? JsonPrimitive)?.contentOrNull ?: (esp.padrao as? JsonPrimitive)?.contentOrNull
            val itens = (if (esp.padrao == null) listOf("" to "(do perfil)") else emptyList()) + esp.valores.map { it to it }
            Menu(rotulo, atualTexto ?: "(do perfil)", itens) { definir(if (it.isEmpty()) null else JsonPrimitive(it)) }
        }
        "inteiro", "numero" -> Numero(rotulo, atual, esp, chave) { definir(it) }
        "texto" -> Texto(rotulo, (atual as? JsonPrimitive)?.contentOrNull.orEmpty(), "", esp.max?.toInt() ?: 200, chave) {
            definir(it?.let(::JsonPrimitive))
        }
        "metrica" -> {
            val numericas = metricas.filter { catalogo.categoria(it.tipo) == "numerica" }
            val id = (atual as? JsonPrimitive)?.contentOrNull
            Menu(rotulo, numericas.firstOrNull { it.id == id }?.nome ?: (id ?: "(nenhuma)"),
                listOf("" to "(nenhuma)") + numericas.map { it.id to it.nome }) { definir(if (it.isEmpty()) null else JsonPrimitive(it)) }
        }
        "objeto" -> if (esp.objetoTudoOuNada) {
            // ex. arco_inferior: existe inteiro ou não existe
            Chave(rotulo, atual is JsonObject) { liga ->
                if (!liga) definir(null) else {
                    val m = metricas.firstOrNull { catalogo.categoria(it.tipo) == "numerica" && it.id != w.metrica } ?: metricas.first()
                    definir(buildJsonObject {
                        for ((k, f) in esp.campos) when (f.tipo) {
                            "metrica" -> put(k, JsonPrimitive(m.id))
                            "numero" -> put(k, JsonPrimitive(if (k == "min") m.escala?.getOrNull(0) ?: 0.0 else m.escala?.getOrNull(1) ?: 100.0))
                        }
                    })
                }
            }
            if (atual is JsonObject) Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, f) in esp.campos) Campo(w, caminho + k, f, k, layout, catalogo, metricas, aoMudar)
            }
        } else {
            Text(rotulo.uppercase(), color = Cor.apagado, fontSize = 11.sp, letterSpacing = 1.sp)
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val conteudo = (EditorLayout.valorOpcao(w.opcoes, listOf("centro", "conteudo")) as? JsonPrimitive)?.contentOrNull
                for ((k, f) in esp.campos) {
                    // centro.metrica só vale quando o centro mostra "outra métrica"
                    if (caminho == listOf("centro") && k == "metrica" && conteudo != "metrica") continue
                    Campo(w, caminho + k, f, k.replace('_', ' '), layout, catalogo, metricas, aoMudar)
                }
            }
        }
    }
}

internal val corCampos @Composable get() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Cor.grande, unfocusedTextColor = Cor.grande, focusedBorderColor = Cor.passou,
    unfocusedBorderColor = Cor.borda, errorBorderColor = Cor.vermelho, errorTextColor = Cor.vermelho, cursorColor = Cor.passou,
    focusedLabelColor = Cor.passou, unfocusedLabelColor = Cor.apagado, errorLabelColor = Cor.vermelho,
    focusedPlaceholderColor = Cor.apagado, unfocusedPlaceholderColor = Cor.apagado)

@Composable
private fun Chave(rotulo: String, ligada: Boolean, aoMudar: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(ligada, aoMudar, colors = SwitchDefaults.colors(
            checkedThumbColor = Cor.verde, checkedTrackColor = Temas.painel.cores.chaveLigadaTrilho, checkedBorderColor = Cor.verde,
            uncheckedThumbColor = Cor.fino, uncheckedTrackColor = Cor.superficie, uncheckedBorderColor = Cor.apagado))
        Text(rotulo, color = Cor.medio, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

/** Escolha numa lista (menu): [itens] = (valor, texto). */
@Composable
private fun Menu(rotulo: String, atual: String, itens: List<Pair<String, String>>, aoEscolher: (String) -> Unit) {
    var aberto by remember { mutableStateOf(false) }
    Column {
        Text(rotulo, color = Cor.apagado, fontSize = 12.sp)
        Box {
            Row(Modifier.fillMaxWidth().clickable { aberto = true }.border(1.dp, Cor.borda, RoundedCornerShape(Temas.painel.raios.rotulo.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(atual, color = Cor.grande, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text("▾", color = Cor.apagado, fontSize = 14.sp)
            }
            DropdownMenu(aberto, { aberto = false }, containerColor = Cor.superficie2) {
                for ((v, t) in itens) DropdownMenuItem(
                    text = { Text(t, color = Cor.grande, fontSize = 14.sp) },
                    onClick = { aberto = false; aoEscolher(v) })
            }
        }
    }
}

@Composable
private fun Texto(rotulo: String, valor: String, dica: String, maximo: Int, chave: String, aoMudar: (String?) -> Unit) {
    var txt by remember(chave) { mutableStateOf(valor) }
    OutlinedTextField(txt, { txt = it.take(maximo); aoMudar(txt.trim().ifEmpty { null }) }, singleLine = true,
        label = { Text(rotulo, fontSize = 12.sp) }, placeholder = { Text(dica, fontSize = 14.sp) },
        textStyle = TextStyle(fontSize = 14.sp), colors = corCampos, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Numero(rotulo: String, atual: JsonElement?, esp: EspOpcao, chave: String, aoMudar: (JsonElement?) -> Unit) {
    var txt by remember(chave) { mutableStateOf((atual as? JsonPrimitive)?.contentOrNull.orEmpty()) }
    val inteiro = esp.tipo == "inteiro"
    fun ler(s: String): JsonElement? {
        val d = s.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (!d.isFinite() || inteiro && d != Math.floor(d)) return null
        if (esp.min != null && d < esp.min || esp.max != null && d > esp.max || esp.minExclusivo != null && d <= esp.minExclusivo) return null
        return JsonPrimitive(if (inteiro) d.toLong() else if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong() else d)
    }
    val dica = esp.padrao?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "do perfil"
    val invalido = txt.isNotBlank() && ler(txt) == null
    OutlinedTextField(txt, { txt = it; if (it.isBlank()) { if (!esp.obrigatorio) aoMudar(null) } else ler(it)?.let(aoMudar) }, singleLine = true,
        isError = invalido, label = { Text(rotulo, fontSize = 12.sp) }, placeholder = { Text(dica, fontSize = 14.sp) },
        supportingText = if (invalido) { { Text(faixaTexto(esp), fontSize = 11.sp) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (inteiro) KeyboardType.Number else KeyboardType.Decimal),
        textStyle = TextStyle(fontSize = 14.sp), colors = corCampos, modifier = Modifier.fillMaxWidth())
}

private fun faixaTexto(esp: EspOpcao): String {
    val partes = mutableListOf<String>()
    if (esp.tipo == "inteiro") partes += "inteiro"
    esp.min?.let { partes += "mín. ${fmt(it)}" }
    esp.max?.let { partes += "máx. ${fmt(it)}" }
    esp.minExclusivo?.let { partes += "maior que ${fmt(it)}" }
    return partes.joinToString(", ").ifEmpty { "valor inválido" }
}

private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
