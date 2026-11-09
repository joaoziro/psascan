package br.psascan.painel.ui.layout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.psascan.painel.protocolo.Metrica
import br.psascan.painel.ui.Cor
import br.psascan.painel.ui.FONTE_PAINEL
import br.psascan.painel.ui.Temas
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/*
 * Modo edição da tela personalizável: a geometria do arrastar e redimensionar e as operações sobre o layout são
 * funções puras (testáveis na JVM, ver EdicaoTest); [EdicaoGrade] é a camada de toque por cima da grade.
 * As regras são as de psascan/static/js/tela.js: encaixe nas células, mínimo e máximo do catálogo, sem sair da
 * grade e sem sobrepor; só solta onde cabe.
 */

/** Retângulo em células: canto (x, y), largura [l] e altura [a]. */
data class Ret(val x: Int, val y: Int, val l: Int, val a: Int) {
    companion object {
        fun de(w: WidgetLayout) = Ret(w.x, w.y, w.largura, w.altura)
    }
}

object Geometria {
    /** O retângulo cabe na grade sem sobrepor outro widget (menos [ignorar], o que está sendo movido)? */
    fun cabe(r: Ret, grade: GradeLayout, widgets: List<WidgetLayout>, ignorar: String? = null): Boolean {
        if (r.l < 1 || r.a < 1 || r.x < 0 || r.y < 0 || r.x + r.l > grade.colunas || r.y + r.a > grade.linhas) return false
        return widgets.none { o ->
            o.id != ignorar && r.x < o.x + o.largura && o.x < r.x + r.l && r.y < o.y + o.altura && o.y < r.y + r.a
        }
    }

    /** Deslocamento em células de um arrasto de [deltaPx] pixels com células de [celulaPx] (arredonda: encaixa na mais próxima). */
    fun celulas(deltaPx: Float, celulaPx: Float): Int = if (celulaPx <= 0f) 0 else (deltaPx / celulaPx).roundToInt()

    /** Mover [dx], [dy] células, sem sair da grade (a sobreposição quem confere é [cabe]). */
    fun mover(o: Ret, dx: Int, dy: Int, grade: GradeLayout) = Ret(
        (o.x + dx).coerceIn(0, grade.colunas - o.l), (o.y + dy).coerceIn(0, grade.linhas - o.a), o.l, o.a)

    /** Esticar [dx], [dy] células pelo canto de baixo à direita, entre o mínimo e o máximo do catálogo e dentro da grade. */
    fun redimensionar(o: Ret, dx: Int, dy: Int, tipo: TipoCatalogo, grade: GradeLayout): Ret {
        val l = (o.l + dx).coerceIn(tipo.minimo[0], maxOf(tipo.minimo[0], minOf(tipo.maximo[0], grade.colunas - o.x)))
        val a = (o.a + dy).coerceIn(tipo.minimo[1], maxOf(tipo.minimo[1], minOf(tipo.maximo[1], grade.linhas - o.y)))
        return Ret(o.x, o.y, l, a)
    }

    /** O primeiro lugar livre (varrendo linha a linha) para um retângulo de [l] × [a]; null se não há. */
    fun primeiroLivre(l: Int, a: Int, grade: GradeLayout, widgets: List<WidgetLayout>): Pair<Int, Int>? {
        for (y in 0..grade.linhas - a) for (x in 0..grade.colunas - l)
            if (cabe(Ret(x, y, l, a), grade, widgets)) return x to y
        return null
    }
}

/** Operações do editor sobre o [Layout] (imutável: cada uma devolve o novo; null = não foi possível). */
object EditorLayout {
    fun metricasAceitas(cat: Catalogo, tipo: String, metricas: List<Metrica>) = metricas.filter { cat.cabe(tipo, it) }
    private fun Catalogo.cabe(tipo: String, m: Metrica) = aceita(tipo, m.tipo)

    fun idNovo(layout: Layout, tipo: String): String {
        var n = 1
        while (layout.widgets.any { it.id == "$tipo-$n" }) n++
        return "$tipo-$n"
    }

    private fun substituir(layout: Layout, id: String, fn: (WidgetLayout) -> WidgetLayout) =
        layout.copy(widgets = layout.widgets.map { if (it.id == id) fn(it) else it })

    /** Aplica a geometria já conferida ([Geometria.cabe]) ao widget. */
    fun geometria(layout: Layout, id: String, r: Ret) = substituir(layout, id) { it.copy(x = r.x, y = r.y, largura = r.l, altura = r.a) }

    fun remover(layout: Layout, id: String) = layout.copy(widgets = layout.widgets.filter { it.id != id })

    /** Um `numero` 2×2 (ou 1×1) no primeiro lugar livre, com a primeira métrica que ele aceita; null se não há espaço. */
    fun adicionar(layout: Layout, cat: Catalogo, metricas: List<Metrica>): Layout? {
        val tipo = cat.tipos.getValue("numero")
        val m = metricasAceitas(cat, "numero", metricas).firstOrNull()
        for ((l, a) in listOf(maxOf(tipo.minimo[0], 2) to maxOf(tipo.minimo[1], 2), tipo.minimo[0] to tipo.minimo[1])) {
            val (x, y) = Geometria.primeiroLivre(l, a, layout.grade, layout.widgets) ?: continue
            return layout.copy(widgets = layout.widgets + WidgetLayout(idNovo(layout, "numero"), "numero", m?.id, x, y, l, a))
        }
        return null
    }

    /** Garante uma métrica que o tipo aceite (ou nenhuma, se o tipo não usa ou a luz vem das falhas). */
    fun ajustarMetrica(w: WidgetLayout, cat: Catalogo, metricas: List<Metrica>): WidgetLayout {
        val tipo = cat.tipos.getValue(w.tipo)
        if (!tipo.usaMetrica || tipo.dispensaMetrica(w.opcoes)) return w.copy(metrica = null)
        val atual = metricas.firstOrNull { it.id == w.metrica }
        if (atual != null && cat.aceita(w.tipo, atual.tipo)) return w
        val prim = metricasAceitas(cat, w.tipo, metricas).firstOrNull() ?: return w
        return w.copy(metrica = prim.id)
    }

    /** Troca o tipo: ajusta o tamanho aos limites do novo tipo (null se não cabe ali), mantém as opções que ele também tem. */
    fun trocarTipo(layout: Layout, cat: Catalogo, id: String, novo: String, metricas: List<Metrica>): Layout? {
        val w = layout.widgets.firstOrNull { it.id == id } ?: return null
        val tipo = cat.tipos[novo] ?: return null
        val l = w.largura.coerceIn(tipo.minimo[0], tipo.maximo[0])
        val a = w.altura.coerceIn(tipo.minimo[1], tipo.maximo[1])
        if (!Geometria.cabe(Ret(w.x, w.y, l, a), layout.grade, layout.widgets, id)) return null
        val opcoes = JsonObject(w.opcoes.filterKeys { it in tipo.opcoes })
        return substituir(layout, id) { ajustarMetrica(it.copy(tipo = novo, largura = l, altura = a, opcoes = opcoes), cat, metricas) }
    }

    fun metrica(layout: Layout, id: String, metrica: String) = substituir(layout, id) { it.copy(metrica = metrica) }

    fun titulo(layout: Layout, id: String, titulo: String?) = substituir(layout, id) { it.copy(titulo = titulo?.trim()?.ifEmpty { null }) }

    /** Define (ou apaga, com [valor] null) a opção em [caminho] (ex. ["escala", "min"]); objeto que ficou vazio some junto. */
    fun opcao(layout: Layout, id: String, caminho: List<String>, valor: JsonElement?, cat: Catalogo, metricas: List<Metrica>) =
        substituir(layout, id) { w -> ajustarMetrica(w.copy(opcoes = definirOpcao(w.opcoes, caminho, valor)), cat, metricas) }

    fun definirOpcao(opcoes: JsonObject, caminho: List<String>, valor: JsonElement?): JsonObject {
        val k = caminho.first()
        val resto = caminho.drop(1)
        val m = opcoes.toMutableMap()
        if (resto.isEmpty()) {
            if (valor == null) m.remove(k) else m[k] = valor
        } else {
            val sub = definirOpcao((opcoes[k] as? JsonObject) ?: JsonObject(emptyMap()), resto, valor)
            if (sub.isEmpty()) m.remove(k) else m[k] = sub
        }
        return JsonObject(m)
    }

    fun valorOpcao(opcoes: JsonObject, caminho: List<String>): JsonElement? =
        caminho.fold(opcoes as JsonElement?) { o, k -> (o as? JsonObject)?.get(k) }

    /** Muda colunas ou linhas da grade; null se algum widget ficaria de fora ou o valor sai da faixa permitida. */
    fun tamanhoGrade(layout: Layout, colunas: Boolean, valor: Int): Layout? {
        val faixa = if (colunas) LimitesLayout.COLUNAS else LimitesLayout.LINHAS
        if (valor !in faixa) return null
        if (layout.widgets.any { (if (colunas) it.x + it.largura else it.y + it.altura) > valor }) return null
        return layout.copy(grade = if (colunas) layout.grade.copy(colunas = valor) else layout.grade.copy(linhas = valor))
    }
}

/** O estado da edição, guardado fora da tela (sobrevive à troca de modo do app): o original, a cópia em edição e o widget escolhido. */
data class EstadoEdicao(val original: Layout, val atual: Layout, val selecionado: String? = null) {
    val sujo get() = atual != original
}

// ---------------------------------------------------------------------------------------------
// Camada de toque

private enum class Gesto { MOVER, REDIMENSIONAR }
private class Arrasto(val id: String, val gesto: Gesto, val origem: Ret, val acumulado: Offset)

/**
 * A grade visível por cima dos widgets (que continuam vivos por baixo) com uma caixa por widget: arrastar pelo
 * corpo move, arrastar pela alça do canto redimensiona, tocar seleciona. O fantasma mostra onde vai cair (verde,
 * cabe; vermelho, não cabe e não solta). [indisponiveis] = ids de widgets que a [Grade] mostra como indisponíveis
 * (borda tracejada).
 */
@Composable
fun EdicaoGrade(
    layout: Layout, catalogo: Catalogo, selecionado: String?, indisponiveis: Set<String>,
    aoSelecionar: (String?) -> Unit, aoGeometria: (String, Ret) -> Unit, modifier: Modifier = Modifier,
) {
    val dens = LocalDensity.current
    val layoutAtual by rememberUpdatedState(layout)
    val selecionar by rememberUpdatedState(aoSelecionar)
    val aplicar by rememberUpdatedState(aoGeometria)
    var arrasto by remember { mutableStateOf<Arrasto?>(null) }
    BoxWithConstraints(modifier) {
        val cw = maxWidth / layout.grade.colunas
        val ch = maxHeight / layout.grade.linhas
        val cwPx = with(dens) { cw.toPx() }
        val chPx = with(dens) { ch.toPx() }
        val cwPxAtual by rememberUpdatedState(cwPx)
        val chPxAtual by rememberUpdatedState(chPx)
        val fundo = Cor.borda
        Canvas(Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            for (y in 0 until layout.grade.linhas) for (x in 0 until layout.grade.colunas)
                drawRoundRect(fundo, Offset(x * cwPx + 1.5.dp.toPx(), y * chPx + 1.5.dp.toPx()),
                    Size(cwPx - 3.dp.toPx(), chPx - 3.dp.toPx()), CornerRadius(3.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = dash))
        }
        fun alvoDe(a: Arrasto): Ret {
            val dx = Geometria.celulas(a.acumulado.x, cwPxAtual); val dy = Geometria.celulas(a.acumulado.y, chPxAtual)
            val w = layoutAtual.widgets.first { it.id == a.id }
            return if (a.gesto == Gesto.MOVER) Geometria.mover(a.origem, dx, dy, layoutAtual.grade)
            else Geometria.redimensionar(a.origem, dx, dy, catalogo.tipos.getValue(w.tipo), layoutAtual.grade)
        }
        for (w in layout.widgets) key(w.id) {
            val wAtual by rememberUpdatedState(w)
            val sel = w.id == selecionado
            val movendo = arrasto?.id == w.id
            val forma = RoundedCornerShape(Temas.painel.raios.rotulo.dp)
            val acento = Cor.passou
            fun comecar(g: Gesto) { arrasto = Arrasto(w.id, g, Ret.de(wAtual), Offset.Zero) }
            fun acabar(aplicar: Boolean) {
                val a = arrasto ?: return
                arrasto = null
                if (!aplicar) return
                val alvo = alvoDe(a)
                if (alvo != a.origem && Geometria.cabe(alvo, layoutAtual.grade, layoutAtual.widgets, a.id)) aplicar(a.id, alvo)
            }
            Box(
                Modifier.offset(cw * w.x, ch * w.y).size(cw * w.largura, ch * w.altura).padding(1.dp)
                    .alpha(if (movendo) .45f else 1f)
                    .background(acento.copy(alpha = if (sel) .16f else .08f), forma)
                    .border(if (sel) 3.dp else 2.dp, if (sel) Cor.grande else acento, forma)
                    .pointerInput(w.id) { detectTapGestures(onTap = { selecionar(w.id) }) }
                    .pointerInput(w.id) {
                        detectDragGestures(
                            onDragStart = { comecar(Gesto.MOVER); selecionar(w.id) },
                            onDrag = { ch2, d -> ch2.consume(); arrasto = arrasto?.let { Arrasto(it.id, it.gesto, it.origem, it.acumulado + d) } },
                            onDragEnd = { acabar(true) }, onDragCancel = { acabar(false) })
                    },
            ) {
                val tipoNome = catalogo.tipos[w.tipo]?.nome ?: w.tipo
                Text(
                    "⠿ " + (w.titulo ?: w.metrica ?: tipoNome) + if (w.id in indisponiveis) " (indisp.)" else "",
                    color = Cor.fundo, fontSize = 11.sp, fontFamily = FONTE_PAINEL, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopStart).padding(end = 28.dp).background(acento, forma)
                        .padding(horizontal = 6.dp, vertical = 1.dp))
                Box(
                    Modifier.align(Alignment.BottomEnd).size(28.dp).background(acento, forma)
                        .pointerInput(w.id) {
                            detectDragGestures(
                                onDragStart = { comecar(Gesto.REDIMENSIONAR); selecionar(w.id) },
                                onDrag = { ch2, d -> ch2.consume(); arrasto = arrasto?.let { Arrasto(it.id, it.gesto, it.origem, it.acumulado + d) } },
                                onDragEnd = { acabar(true) }, onDragCancel = { acabar(false) })
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("◢", color = Cor.fundo, fontSize = 14.sp) }
            }
        }
        // fantasma: onde o widget cai se soltar agora
        arrasto?.let { a ->
            val alvo = alvoDe(a)
            val ok = Geometria.cabe(alvo, layout.grade, layout.widgets, a.id)
            val cor = if (ok) Cor.verde else Cor.vermelho
            Box(Modifier.offset(cw * alvo.x, ch * alvo.y).size(cw * alvo.l, ch * alvo.a).padding(1.dp)
                .background(cor.copy(alpha = .28f), RoundedCornerShape(Temas.painel.raios.rotulo.dp))
                .border(2.dp, cor, RoundedCornerShape(Temas.painel.raios.rotulo.dp)))
        }
    }
}
