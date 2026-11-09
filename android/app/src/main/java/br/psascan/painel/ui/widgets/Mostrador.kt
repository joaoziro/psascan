package br.psascan.painel.ui.widgets

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import br.psascan.painel.ui.AnimacaoPonteiro
import br.psascan.painel.ui.AnimacaoTema
import br.psascan.painel.ui.Cores
import br.psascan.painel.ui.EstiloPonteiro
import br.psascan.painel.ui.Painel
import br.psascan.painel.ui.PeleMostrador
import br.psascan.painel.ui.layout.Contexto
import br.psascan.painel.ui.layout.TipoWidget
import br.psascan.painel.ui.layout.decimais
import br.psascan.painel.ui.layout.numBr
import br.psascan.painel.ui.layout.rememberFontePele
import br.psascan.painel.ui.layout.rememberImagemTema
import br.psascan.painel.ui.sobrepor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Widget `mostrador`: relógio de ponteiro dos anos 90, o mesmo desenho do painel do carro (e de static/widgets/mostrador.js).
 * Tudo é desenhado num quadrado de 200 × 200 unidades (centro 100,100) e escalado para o espaço do widget; ângulos em
 * graus a partir do topo, no sentido horário. Camadas, de baixo para cima (docs/TEMA.md, "Mostrador"):
 * fundo (imagem) → aro → escala e números → arco da pele / máscara → barra do arco de baixo → agulha → miolo →
 * leituras do centro (digital, rolos) e temperatura do arco de baixo → vidro.
 * Sem pele, as cores e espessuras do tema bastam; a pele troca peças por imagens, ângulos, arco e digital.
 */

/** Escala de 200 unidades → pixels, e coordenadas polares em volta do centro. */
private class Plano(val s: Float) {
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun pol(r: Float, g: Float): Offset {
        val a = (g - 90) * PI / 180
        return Offset((100 + r * cos(a)).toFloat() * s, (100 + r * sin(a)).toFloat() * s)
    }
}

/** Tudo o que o desenho de um quadro precisa, calculado na composição. */
private class Visual(
    val T: Painel, val C: Cores, val pele: PeleMostrador, val es: EscalaMostrador, val g0: Float, val g1: Float,
    val m: TextMeasurer, val familia: FontFamily, val digitalFamilia: FontFamily,
    val fundo: ImageBitmap?, val agulha: ImageBitmap?, val miolo: ImageBitmap?, val vidro: ImageBitmap?, val mascara: ImageBitmap?,
    val dAro: Boolean, val dEscala: Boolean, val dNumeros: Boolean, val dPonteiro: Boolean, val dMiolo: Boolean,
) {
    fun ang(v: Double) = anguloDe(v, es.min, es.max, g0, g1)
}

// arco de baixo (o da água): do frio (AG1, à esquerda) ao quente (AG0)
private const val AG0 = 148f
private const val AG1 = 212f

object WidgetMostrador : TipoWidget {
    override val tipo = "mostrador"

    @Composable
    override fun Desenhar(c: Contexto, modifier: Modifier) {
        val tema = c.tema
        val pele = tema.peles.mostrador
        val cores = remember(tema.cores, pele.cores) { tema.cores.sobrepor(pele.cores) }
        val im = pele.imagens
        val fundo = rememberImagemTema(c.temaId, im.fundo)
        val agulha = rememberImagemTema(c.temaId, im.agulha?.arquivo)
        val miolo = rememberImagemTema(c.temaId, im.miolo)
        val vidro = rememberImagemTema(c.temaId, im.vidro)
        val mascara = rememberImagemTema(c.temaId, im.mascaraArco)
        val familia = rememberFontePele(c.temaId, null)
        val familiaDigital = rememberFontePele(c.temaId, pele.digital?.fonteApp)
        val m = rememberTextMeasurer(cacheSize = 96)
        val d = pele.desenhar
        val es = escalaEfetiva(c.opcoes, c.metrica?.escala?.getOrNull(0), c.metrica?.escala?.getOrNull(1))
        val vis = Visual(
            tema, cores, pele, es, pele.anguloInicio, pele.anguloFim, m, familia, familiaDigital, fundo, agulha, miolo, vidro, mascara,
            dAro = d.aro ?: (im.fundo == null), dEscala = d.escala ?: (im.fundo == null), dNumeros = d.numeros ?: (im.fundo == null),
            dPonteiro = d.ponteiro ?: (im.agulha == null), dMiolo = d.miolo ?: (im.miolo == null),
        )

        // arco de baixo e centro: outras métricas citadas nas opções
        val ai = c.opcoes.objeto("arco_inferior")?.takeIf { it.texto("metrica") != null }
        val exA = ai?.texto("metrica")?.let { c.extra[it] }
        val aMin = ai?.numero("min") ?: 0.0
        val aMax = ai?.numero("max") ?: (aMin + 1)
        val alvoA = exA?.valor?.numero
        val centro = c.opcoes.objeto("centro")
        val conteudo = centro?.texto("conteudo") ?: "digital"
        val exC = if (conteudo == "metrica") centro?.texto("metrica")?.let { c.extra[it] } else null
        val alvoX = exC?.valor?.numero

        // animação: o ponteiro segue o valor como o tema manda (mola, suave ou direto)
        val alvoV = c.valor?.numero
        val limite = es.max + es.faixa * .02
        val v by animateFloatAsState((alvoV ?: es.min).coerceIn(es.min, limite).toFloat(), especificacao(tema.animacao, false), label = "ponteiro")
        val a by animateFloatAsState((alvoA ?: aMin).toFloat(), especificacao(tema.animacao, true), label = "arco-inferior")

        val lado = max(1f, min(c.area.largura.value, c.area.altura.value))
        val dec = decimais(c)
        val unidadeRolos = (c.metrica?.unidade?.ifEmpty { null } ?: c.titulo).uppercase()
        Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(lado.dp)) {
                val pl = Plano(size.minDimension / 200f)
                val vv = v.toDouble()
                val porCima = tema.escala.numerosPorCima
                vis.fundo?.let { imagemQuadro(it) }
                if (vis.dAro) aro(vis, pl)
                if (vis.dEscala) escala(vis, pl, vv)
                if (vis.dNumeros && !porCima) numeros(vis, pl, vv)
                arcoDaPele(vis, pl, vv)
                vis.mascara?.let { mascara(vis, pl, vv, it) }
                if (ai != null) arcoInferior(vis, pl, if (alvoA == null) null else a.toDouble(), alvoA, aMin, aMax)
                if (vis.dPonteiro) ponteiro(vis, pl, vis.ang(vv).toFloat())
                vis.agulha?.let { agulhaImagem(vis, pl, vis.ang(vv).toFloat(), it) }
                if (vis.dNumeros && porCima) numeros(vis, pl, vv)
                if (vis.dMiolo) miolo(vis, pl)
                vis.miolo?.let { imagemQuadro(it) }
                if (ai != null) temperatura(vis, pl, alvoA, aMin, aMax, (exA?.metrica?.decimais ?: 0), exA?.metrica?.unidade.orEmpty())
                when (conteudo) {
                    "rolos" -> rolos(vis, pl, unidadeRolos, alvoV)
                    "digital" -> digital(vis, pl, if (alvoV == null) null else vv, dec, es.max, c.metrica?.unidade.orEmpty())
                    "metrica" -> digital(vis, pl, alvoX, exC?.metrica?.decimais ?: 0, exC?.metrica?.escala?.getOrNull(1),
                        exC?.metrica?.unidade.orEmpty())
                }
                vis.vidro?.let { imagemQuadro(it) }
            }
        }
    }
}

/** O movimento do ponteiro (e da barra do arco de baixo, mais lenta na mola, como a da água sempre foi). */
private fun especificacao(a: AnimacaoTema, arcoInferior: Boolean): AnimationSpec<Float> = when (a.ponteiro) {
    AnimacaoPonteiro.MOLA ->   // 7 = a mola de sempre
        if (arcoInferior) spring(1f, Spring.StiffnessVeryLow) else spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow * a.rapidez / 7f)
    AnimacaoPonteiro.SUAVE -> tween((1400f / a.rapidez).toInt(), easing = FastOutSlowInEasing)
    AnimacaoPonteiro.NENHUMA -> snap()
}

// ---------------------------------------------------------------------------------------------
// Texto e imagens

private fun DrawScope.texto(
    v: Visual, pl: Plano, t: String, centro: Offset, tam: Float, cor: Color, peso: FontWeight = FontWeight.Normal,
    espaco: Float = 0f, contorno: Float = 0f, familia: FontFamily = v.familia, brilho: Shadow? = null, tabular: Boolean = false,
) {
    val estilo = TextStyle(color = cor, fontSize = (tam * pl.s).toSp(), fontWeight = peso, letterSpacing = (espaco * pl.s).toSp(),
        fontFamily = familia, fontFeatureSettings = if (tabular) "tnum" else null, shadow = brilho)
    val r = v.m.measure(t, estilo)
    val canto = Offset(centro.x - r.size.width / 2f, centro.y - r.size.height / 2f)
    // cor e traço são de desenho, não de layout: o TextMeasurer devolve o mesmo layout em cache,
    // então os dois passes usam o mesmo `r` com cor e estilo explícitos
    if (contorno > 0f)    // contorno escuro em volta do número
        drawText(r, color = v.C.contornoNumeros, topLeft = canto, shadow = null,
            drawStyle = Stroke(contorno * pl.s, join = StrokeJoin.Round))
    drawText(r, color = cor, topLeft = canto, shadow = brilho, drawStyle = Fill)
}

/** Imagem que cobre o quadrado inteiro, centralizada e sem distorcer. */
private fun DrawScope.imagemQuadro(img: ImageBitmap) {
    val lado = size.minDimension
    val k = min(lado / img.width, lado / img.height)
    desenharImagem(img, (size.width - img.width * k) / 2f, (size.height - img.height * k) / 2f, img.width * k, img.height * k)
}

private fun DrawScope.desenharImagem(img: ImageBitmap, x: Float, y: Float, w: Float, h: Float) =
    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(Math.round(x), Math.round(y)),
        IntSize(Math.round(w).coerceAtLeast(1), Math.round(h).coerceAtLeast(1)), filterQuality = FilterQuality.High)

// ---------------------------------------------------------------------------------------------
// Camadas

/** Aro fino com volume e o fundo um pouco mais claro no meio (profundidade). Com fundo em imagem, só o anel (não cobre a arte). */
private fun DrawScope.aro(v: Visual, pl: Plano) {
    val c = pl.p(100f, 100f)
    val ri = 99.5f - v.T.espessuras.aro          // raio de dentro do aro
    val (a0, a1, a2) = v.C.aro
    val (f0, f1, f2) = v.C.face
    val degrade = Brush.verticalGradient(0f to a0, .5f to a1, 1f to a2, startY = c.y - 99.5f * pl.s, endY = c.y + 99.5f * pl.s)
    if (v.pele.imagens.fundo != null) {
        drawCircle(degrade, radius = (99.5f + ri) / 2f * pl.s, center = c, style = Stroke(v.T.espessuras.aro * pl.s))
        drawCircle(v.C.aroInterno, radius = (ri - .4f) * pl.s, center = c, style = Stroke(.8f * pl.s))
        return
    }
    drawCircle(degrade, radius = 99.5f * pl.s, center = c)
    drawCircle(v.C.aroInterno, radius = ri * pl.s, center = c)
    drawCircle(Brush.radialGradient(0f to f0, .7f to f1, 1f to f2, center = c, radius = (ri - .8f) * pl.s),
        radius = (ri - .8f) * pl.s, center = c)
}

/** Régua fina na borda, traço médio e barra grossa. Pinta com a cor "passou" até [valor] (se o tema pedir). */
private fun DrawScope.escala(v: Visual, pl: Plano, valor: Double) {
    val e = v.es
    val esp = v.T.espessuras
    val k = v.C
    val tres = v.T.escala.niveis == 3
    val passar = v.T.escala.pintarPassou
    val alerta = e.alerta
    if (alerta != null && alerta < e.max) {
        val r = 89.6f * pl.s
        val g = v.ang(max(alerta, e.min))
        drawArc(k.alerta, g - 90, v.g1 - g, false, pl.p(100f, 100f) - Offset(r, r), Size(2 * r, 2 * r), style = Stroke(esp.alerta * pl.s))
    }
    for (i in 0..e.total) {
        val x = e.valorDe(i)
        val g = v.ang(x).toFloat()
        val quente = alerta != null && x >= alerta - 1e-9
        val passou = passar && passouPeloPonteiro(x, valor, e.min)
        val nivel = nivelDe(i, e.menor)
        fun cor(normal: Color) = if (quente) k.alerta else if (passou) k.passou else normal
        // régua fina contínua por baixo dos traços médios e grandes
        if (tres && nivel != NivelTraco.FINO)
            drawLine(cor(k.textoFino), pl.pol(88.4f, g), pl.pol(85f, g), esp.tracoFino * pl.s)
        when (nivel) {
            NivelTraco.GRANDE -> drawLine(cor(k.texto), pl.pol(84f, g), pl.pol(72.5f, g), esp.tracoGrande * pl.s)
            NivelTraco.MEDIO -> drawLine(cor(k.textoMedio), pl.pol(84f, g), pl.pol(78f, g), esp.tracoMedio * pl.s)
            NivelTraco.FINO -> if (tres) drawLine(cor(k.textoFino), pl.pol(88.4f, g), pl.pol(85f, g), esp.tracoFino * pl.s)
        }
    }
}

/** Números da escala, com contorno (para ficarem legíveis também por cima do ponteiro). */
private fun DrawScope.numeros(v: Visual, pl: Plano, valor: Double) {
    val e = v.es
    val (rNum, tamNum) = medidasDosNumeros(e)
    var i = 0
    while (i <= e.total) {
        val x = e.valorDe(i)
        val quente = e.alerta != null && x >= e.alerta - 1e-9
        val passou = v.T.escala.pintarPassou && passouPeloPonteiro(x, valor, e.min)
        val cor = if (quente) v.C.alerta else if (passou) v.C.passou else v.C.texto
        texto(v, pl, fmtRotulo(x / e.div), pl.pol(rNum, v.ang(x).toFloat()), tamNum, cor, FontWeight.Medium, -.3f,
            contorno = v.T.espessuras.contornoNumeros)
        i += e.menor
    }
}

/** O arco da pele (alternativa à máscara): contínuo ou em segmentos, aceso até o valor e vermelho depois do alerta. */
private fun DrawScope.arcoDaPele(v: Visual, pl: Plano, valor: Double) {
    val a = v.pele.arco?.takeIf { it.mostrar } ?: return
    val e = v.es
    val cor = a.cor ?: v.C.ok
    val corAl = a.corAlerta ?: v.C.alerta
    val r = a.raio * pl.s
    val canto = pl.p(100f, 100f) - Offset(r, r)
    val tam = Size(2 * r, 2 * r)
    val esp = Stroke(a.espessura * pl.s)
    val f = frac(valor, e.min, e.max)
    if (a.estilo == "segmentos") {
        val n = max(2, a.segmentos)
        val passo = (v.g1 - v.g0) / n
        val folga = passo * .15f
        val cores = segmentosDoArco(n, f, e.min, e.max, e.alerta)
        for (i in 0 until n) {
            val c = when (cores[i]) { CorSegmento.APAGADO -> v.C.aguaTrilho; CorSegmento.NORMAL -> cor; CorSegmento.ALERTA -> corAl }
            val a0 = v.g0 + i * passo + folga / 2
            drawArc(c, a0 - 90, passo - folga, false, canto, tam, style = esp)
        }
    } else {
        drawArc(v.C.aguaTrilho, v.g0 - 90, min(v.g1 - v.g0, 359.9f), false, canto, tam, style = esp)
        val t = trechosDoArco(f, v.g0, v.g1, e.alerta?.let { v.ang(it) })
        t.aceso?.let { drawArc(cor, it.start - 90, it.endInclusive - it.start, false, canto, tam, style = esp) }
        t.alerta?.let { drawArc(corAl, it.start - 90, it.endInclusive - it.start, false, canto, tam, style = esp) }
    }
}

/** A imagem do arco "aceso", recortada num setor do início até o valor. */
private fun DrawScope.mascara(v: Visual, pl: Plano, valor: Double, img: ImageBitmap) {
    val gv = v.g0 + frac(valor, v.es.min, v.es.max).toFloat() * (v.g1 - v.g0)
    val varr = min(gv - v.g0, 359.9f)
    if (varr < .05f) return
    val c = pl.p(100f, 100f)
    val r = 142f * pl.s
    val setor = Path().apply {
        moveTo(c.x, c.y)
        val p0 = pl.pol(142f, v.g0)
        lineTo(p0.x, p0.y)
        arcTo(Rect(c.x - r, c.y - r, c.x + r, c.y + r), v.g0 - 90, varr, false)
        close()
    }
    clipPath(setor) { imagemQuadro(img) }
}

/** A barra pequena do arco de baixo (a da água): trilho, preenchimento até o valor, marcas e C/H. [a] = valor animado. */
private fun DrawScope.arcoInferior(v: Visual, pl: Plano, a: Double?, alvo: Double?, aMin: Double, aMax: Double) {
    val k = v.C
    val r = 55.25f * pl.s
    val canto = pl.p(100f, 100f) - Offset(r, r)
    val esp = v.T.espessuras.agua * pl.s
    val angAg = { x: Double -> AG1 - frac(x, aMin, aMax).toFloat() * (AG1 - AG0) }
    drawArc(k.aguaTrilho, AG0 - 90, AG1 - AG0, false, canto, Size(2 * r, 2 * r), style = Stroke(esp))
    if (a != null) {
        val limite = min(angAg(a), AG1 - .5f)
        drawArc(corDoArcoInferior(v, alvo, aMin, aMax), limite - 90, AG1 - limite, false, canto, Size(2 * r, 2 * r), style = Stroke(esp))
    }
    // marcas: meio (grande), quartos (finas) e o início da zona de alerta
    for ((f, r0, r1, w, cor) in listOf(
        Marca(.5f, 50f, 60.5f, 1.6f, k.texto), Marca(.25f, 52.5f, 59.5f, .8f, k.textoFino),
        Marca(.75f, 52.5f, 59.5f, .8f, k.textoFino), Marca(.9625f, 51f, 60f, 2.2f, k.alerta)))
        drawLine(cor, pl.pol(r0, AG1 - f * (AG1 - AG0)), pl.pol(r1, AG1 - f * (AG1 - AG0)), w * pl.s)
    texto(v, pl, "C", pl.pol(47f, AG1 - 3) - Offset(0f, 4f * pl.s), 6.5f, k.textoMedio)
    texto(v, pl, "H", pl.pol(47f, AG0 + 3) - Offset(0f, 4f * pl.s), 6.5f, k.textoMedio)
}

private data class Marca(val f: Float, val r0: Float, val r1: Float, val w: Float, val cor: Color)

/** Cor do arco de baixo pela fração da faixa: vermelho a partir de 75 %, "passou" abaixo de 25 %, o resto "ok". */
private fun corDoArcoInferior(v: Visual, alvo: Double?, aMin: Double, aMax: Double): Color =
    when (nivelDoArcoInferior(alvo?.let { frac(it, aMin, aMax) })) {
        NivelArcoInferior.SEM_VALOR -> v.C.textoFino
        NivelArcoInferior.BAIXO -> v.C.passou
        NivelArcoInferior.ALTO -> v.C.alerta
        NivelArcoInferior.MEIO -> v.C.ok
    }

/** Termômetro e valor do arco de baixo, por cima do miolo (como no painel). */
private fun DrawScope.temperatura(v: Visual, pl: Plano, alvo: Double?, aMin: Double, aMax: Double, dec: Int, unidade: String) {
    val cor = corDoArcoInferior(v, alvo, aMin, aMax)
    val t = Path().apply {
        moveTo(87f * pl.s, 128f * pl.s); lineTo(87f * pl.s, 133.2f * pl.s)
        moveTo(89.6f * pl.s, 129.5f * pl.s); lineTo(91.2f * pl.s, 129.5f * pl.s)
        moveTo(89.6f * pl.s, 131.5f * pl.s); lineTo(90.8f * pl.s, 131.5f * pl.s)
    }
    drawPath(t, cor, style = Stroke(.9f * pl.s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(cor, 1.6f * pl.s, pl.p(87f, 134.6f), style = Stroke(.8f * pl.s))
    texto(v, pl, if (alvo == null) "—" else numBr(alvo, dec) + if (unidade.isNotEmpty()) " $unidade" else "", pl.p(102f, 132f), 7.5f, cor,
        tabular = true)
}

/**
 * Ponteiro no estilo do tema: agulha (afina até a ponta), seta (agulha com cabeça) ou barra (largura constante).
 * Tinta em degradê com aresta clara, brilho leve e sombra. Começa sob o miolo.
 */
private fun DrawScope.ponteiro(v: Visual, pl: Plano, g: Float) {
    val t = v.T
    val k = v.C
    val r0 = t.ponteiro.raioInicio; val r1 = t.ponteiro.raioPonta
    val base = t.espessuras.ponteiroBase
    val ponta = if (t.ponteiro.estilo == EstiloPonteiro.BARRA) base else t.espessuras.ponteiroPonta
    val cabeca = base * 1.7f                        // meia largura da cabeça da seta
    val rCabeca = max(r0 + 1f, r1 - 10f)            // onde a seta começa
    val larg = if (t.ponteiro.estilo == EstiloPonteiro.SETA) max(base, cabeca) else base
    val rad = Math.toRadians(g.toDouble())
    val px = cos(rad).toFloat() * pl.s; val py = sin(rad).toFloat() * pl.s   // perpendicular à agulha
    fun forma(dx: Float = 0f, dy: Float = 0f) = Path().apply {
        val d = Offset(dx * pl.s, dy * pl.s)
        fun ponto(r: Float, lado: Float, meia: Float): Offset {
            val a = pl.pol(r, g)
            return Offset(a.x + lado * meia * px + d.x, a.y + lado * meia * py + d.y)
        }
        val seq = if (t.ponteiro.estilo == EstiloPonteiro.SETA)
            listOf(ponto(r0, -1f, base), ponto(rCabeca, -1f, ponta), ponto(rCabeca, -1f, cabeca),
                ponto(r1, 0f, 0f), ponto(rCabeca, 1f, cabeca), ponto(rCabeca, 1f, ponta), ponto(r0, 1f, base))
        else listOf(ponto(r0, -1f, base), ponto(r1, -1f, ponta), ponto(r1, 1f, ponta), ponto(r0, 1f, base))
        moveTo(seq[0].x, seq[0].y); for (o in seq.drop(1)) lineTo(o.x, o.y)
        close()
    }
    val p = forma()
    val bri = t.ponteiro.brilho / .55f              // o Padrão (0,55) desenha o brilho de sempre
    for ((w, alfa) in listOf(5f to .10f, 3f to .18f))                // brilho leve
        drawPath(p, k.ponteiroBrilho.copy(alpha = (alfa * bri).coerceIn(0f, 1f)), style = Stroke(w * pl.s, join = StrokeJoin.Round))
    drawPath(forma(.9f, 1.6f), k.sombra.copy(alpha = t.ponteiro.sombra))   // sombra no fundo
    val meio = pl.pol((r0 + r1) / 2, g)
    val (c0, c1, c2, c3) = k.ponteiro
    drawPath(p, Brush.linearGradient(0f to c0, .48f to c1, .52f to c2, 1f to c3,
        start = meio - Offset(larg * px, larg * py), end = meio + Offset(larg * px, larg * py)))
    drawLine(k.ponteiroAresta.copy(alpha = .3f), pl.pol(r0, g), pl.pol(r1 - 1, g), .35f * pl.s)
}

/** A imagem da agulha (apontando para cima), girada em volta do centro: `pivo` é o ponto de giro na imagem. */
private fun DrawScope.agulhaImagem(v: Visual, pl: Plano, g: Float, img: ImageBitmap) {
    val ag = v.pele.imagens.agulha ?: return
    val altura = ag.comprimento * 200f
    val largura = altura * img.width / img.height
    val x = 100f - ag.pivo[0] * largura
    val y = 100f - ag.pivo[1] * altura
    rotate(g, pl.p(100f, 100f)) { desenharImagem(img, x * pl.s, y * pl.s, largura * pl.s, altura * pl.s) }
}

/** Miolo por cima do ponteiro: disco escuro em relevo, borda chanfrada e sombra no fundo. */
private fun DrawScope.miolo(v: Visual, pl: Plano) {
    val k = v.C
    val r = v.T.raios.miolo
    val c = pl.p(100f, 100f)
    val (m0, m1) = k.miolo
    val (b0, b1, b2) = k.chanfro
    drawCircle(k.sombra.copy(alpha = .55f), (r + .8f) * pl.s, c + Offset(.9f * pl.s, 1.6f * pl.s))
    drawCircle(Brush.radialGradient(0f to m0, 1f to m1, center = c - Offset(0f, .24f * r * pl.s), radius = 1.3f * r * pl.s), r * pl.s, c)
    drawCircle(Brush.verticalGradient(0f to b0, .5f to b1, 1f to b2, startY = c.y - r * pl.s, endY = c.y + r * pl.s),
        (r - .5f) * pl.s, c, style = Stroke(1f * pl.s))
    drawCircle(k.sombra.copy(alpha = .6f), (r - 3f) * pl.s, c, style = Stroke(.6f * pl.s))
}

/** Leitura exata em "rolos" de odômetro, dentro do miolo; o dos milhares destacado. */
private fun DrawScope.rolos(v: Visual, pl: Plano, rotulo: String, alvo: Double?) {
    val k = v.C
    texto(v, pl, rotulo, pl.p(100f, 85f), 7f, k.textoFino, espaco = 1.8f)
    val digitos = digitosDosRolos(alvo, v.es.faixa)
    for (i in 0 until 4) {
        val x = 78.5f + i * 11f
        val milhar = i == 0
        drawRoundRect(if (milhar) k.roloDestaqueFundo else k.roloFundo, pl.p(x, 94f), Size(10f * pl.s, 14f * pl.s), CornerRadius(1.2f * pl.s))
        drawRoundRect(if (milhar) k.roloDestaqueBorda else k.roloBorda, pl.p(x, 94f), Size(10f * pl.s, 14f * pl.s), CornerRadius(1.2f * pl.s),
            style = Stroke(.6f * pl.s))
        texto(v, pl, digitos[i].toString(), pl.p(x + 5f, 101.4f), 10f, if (milhar) k.roloDestaqueTexto else k.roloTexto, tabular = true)
    }
}

/**
 * O número do centro (a métrica do widget ou a de `centro.metrica`), com a unidade embaixo. A pele `digital` troca
 * posição, tamanho, fonte, cor, brilho e mostra os dígitos apagados ("8888" fraco atrás do valor).
 */
private fun DrawScope.digital(v: Visual, pl: Plano, valor: Double?, dec: Int, maximo: Double?, unidade: String) {
    val pd = v.pele.digital
    val px = pd?.posicao?.getOrNull(0) ?: 100f
    val py = pd?.posicao?.getOrNull(1) ?: 95f
    val tam = pd?.tamanho ?: 34f
    val cor = pd?.cor ?: v.C.texto
    val fantasma = if (pd?.apagados == true && maximo != null) numBr(maximo, dec).replace(Regex("\\d"), "8") else null
    val espaco = if (pd != null) 0f else -.5f
    val centro = pl.p(px, py)
    val familia = v.digitalFamilia
    if (fantasma != null) texto(v, pl, fantasma, centro, tam, cor.copy(alpha = cor.alpha * .08f), espaco = espaco, familia = familia, tabular = true)
    var s = if (valor == null) "—" else numBr(valor, dec)
    if (fantasma != null) s = s.padStart(fantasma.length, ' ')
    val brilho = pd?.takeIf { it.brilho > 0f }?.let {
        Shadow(cor.copy(alpha = it.brilho), Offset.Zero, 2f * max(.5f, tam * .07f) * pl.s)
    }
    texto(v, pl, s, centro, tam, cor, espaco = espaco, familia = familia, brilho = brilho, tabular = true)
    if (unidade.isNotEmpty()) texto(v, pl, unidade, pl.p(px, py + tam * .62f), max(5f, tam * .235f), v.C.textoFino)
}
