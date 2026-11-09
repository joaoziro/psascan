package br.psascan.painel.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.psascan.painel.Diagnostico
import br.psascan.painel.protocolo.Avaliacao
import br.psascan.painel.protocolo.Metrica
import br.psascan.painel.protocolo.Perfil
import br.psascan.painel.ui.Cor
import br.psascan.painel.ui.FONTE_PAINEL
import br.psascan.painel.ui.Painel
import br.psascan.painel.ui.Temas

/**
 * Tudo o que a grade lê do app para montar o contexto de cada widget (nada além disto chega aos widgets):
 * o perfil, o catálogo, a última [Diagnostico] da Conexao (valores, avaliações das faixas e o instante "agora"),
 * a última leitura de falhas e o histórico por métrica (ordem, segundos) → lista (segundos, número).
 */
class FonteDados(
    val perfil: Perfil,
    val catalogo: Catalogo,
    val diag: Diagnostico,
    val falhas: FalhasLidas?,
    val historico: (ordem: Int, segundos: Double) -> List<Pair<Double, Double>>,
)

private val NOME_CATEGORIA = mapOf("numerica" to "numérica", "discreta" to "discreta", "bit" to "de bit")

/** Por que o widget não pode ser montado com este perfil? (texto) ou null se pode. Como `motivoIndisponivel` do host.js. */
fun motivoIndisponivel(w: WidgetLayout, f: FonteDados): String? {
    val tipo = f.catalogo.tipos[w.tipo] ?: return "tipo \"${w.tipo}\" desconhecido"
    if (tipo.usaMetrica && !tipo.dispensaMetrica(w.opcoes)) {
        val m = w.metrica?.let(f.perfil::metricaPorId) ?: return "a métrica \"${w.metrica ?: "?"}\" não existe neste perfil"
        if (!f.catalogo.aceita(w.tipo, m.tipo)) {
            val cat = NOME_CATEGORIA[f.catalogo.categoria(m.tipo)] ?: m.tipo
            return "o widget \"${tipo.nome}\" não aceita a métrica ${m.curto} ($cat)"
        }
    }
    for ((caminho, id) in f.catalogo.metricasDasOpcoes(w.tipo, w.opcoes))
        if (f.perfil.metricaPorId(id) == null) return "a métrica \"$id\" (opção $caminho) não existe neste perfil"
    return null
}

private fun valorDe(m: Metrica, f: FonteDados): ValorLido? =
    f.diag.valores[m.ordem]?.let { ValorLido(it.numero, it.texto, it.raw) }

private fun avaliacaoDe(a: Avaliacao?, f: FonteDados): AvaliacaoWidget {
    if (a == null) return AvaliacaoWidget()
    val regra = a.regra
    return AvaliacaoWidget(a.estado, a.lado, a.msg, regra?.let { f.perfil.condicoes[it.quando]?.rotulo }.orEmpty(), regra?.normal)
}

/** Monta o contexto de um widget (a cada leitura). Lança exceção se algo estiver fora do esperado: a [Grade] isola o erro na célula. */
fun montarContexto(w: WidgetLayout, f: FonteDados, tema: Painel, temaId: String, area: AreaWidget): Contexto {
    val m = w.metrica?.let(f.perfil::metricaPorId)
    val extra = HashMap<String, ExtraWidget>()
    for ((_, id) in f.catalogo.metricasDasOpcoes(w.tipo, w.opcoes))
        f.perfil.metricaPorId(id)?.let { extra[id] = ExtraWidget(it, valorDe(it, f)) }
    return Contexto(
        metrica = m,
        valor = m?.let { valorDe(it, f) },
        avaliacao = avaliacaoDe(m?.let { f.diag.avaliacoes[it.ordem] }, f),
        extra = extra,
        falhas = f.falhas,
        tema = tema,
        temaId = temaId,
        area = area,
        opcoes = Opcoes(f.catalogo.completarOpcoes(w.tipo, w.opcoes)),
        titulo = w.titulo ?: m?.curto.orEmpty(),
        agora = f.diag.agora,
        historicoDe = { seg -> m?.let { f.historico(it.ordem, seg) } ?: emptyList() },
    )
}

/**
 * A grade da tela personalizável: `colunas × linhas` células que esticam para ocupar a área; cada widget fica no
 * seu retângulo. Widget com métrica ausente ou de categoria que o tipo não aceita aparece como "indisponível: <motivo>";
 * tipo sem implementação, como "módulo ausente: <tipo>". Erro ao montar o contexto de um widget só afeta a célula dele.
 */
@Composable
fun Grade(layout: Layout, fonte: FonteDados, modifier: Modifier = Modifier) {
    val tema = Temas.painel
    val temaId = Temas.tema.id
    val dens = LocalDensity.current
    BoxWithConstraints(modifier) {
        val cw = maxWidth / layout.grade.colunas
        val ch = maxHeight / layout.grade.linhas
        for (w in layout.widgets) key(w.id) {
            val larg = cw * w.largura
            val alt = ch * w.altura
            Box(Modifier.offset(cw * w.x, ch * w.y).size(larg, alt).padding(CELULA_FOLGA).clipToBounds()) {
                val dl = larg - CELULA_FOLGA * 2
                val da = alt - CELULA_FOLGA * 2
                val area = with(dens) { AreaWidget(dl, da, dl.toPx(), da.toPx()) }
                Celula(w, fonte, tema, temaId, area)
            }
        }
    }
}

/** Folga entre os widgets vizinhos. */
val CELULA_FOLGA = 2.dp

@Composable
private fun Celula(w: WidgetLayout, fonte: FonteDados, tema: Painel, temaId: String, area: AreaWidget) {
    val motivo = motivoIndisponivel(w, fonte)
    val impl = Widgets.porTipo[w.tipo]
    val m = w.metrica?.let(fonte.perfil::metricaPorId)
    val nome = w.titulo ?: m?.curto ?: w.metrica ?: w.tipo
    if (motivo != null) return AvisoWidget(nome, "indisponível: $motivo")
    if (impl == null) return AvisoWidget(w.tipo, "módulo ausente: ${w.tipo}", erro = true)
    val c = try { montarContexto(w, fonte, tema, temaId, area) } catch (e: Exception) {
        return AvisoWidget("erro no widget", "${w.id}: ${e.message ?: e.javaClass.simpleName}", erro = true)
    }
    impl.Desenhar(c, Modifier.fillMaxSize())
}

/** A célula tracejada com título e texto, para o que não pôde ser desenhado. */
@Composable
fun AvisoWidget(titulo: String, texto: String, erro: Boolean = false) {
    val cor = if (erro) Cor.vermelho else Cor.apagado
    val raio = Temas.painel.raios.rotulo
    Column(
        Modifier.fillMaxSize().drawBehind {
            drawRoundRect(cor.copy(alpha = .6f), cornerRadius = CornerRadius(raio.dp.toPx()),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
        }.padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(titulo.uppercase(), color = if (erro) Cor.vermelho else Cor.medio, fontSize = 11.sp, letterSpacing = 0.7.sp,
            fontFamily = FONTE_PAINEL, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(texto, color = cor, fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center, maxLines = 4,
            overflow = TextOverflow.Ellipsis)
    }
}
