package br.psascan.painel.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import br.psascan.painel.FalhaLida
import br.psascan.painel.protocolo.Estado
import br.psascan.painel.protocolo.Lado
import br.psascan.painel.protocolo.Metrica
import br.psascan.painel.ui.Painel
import br.psascan.painel.ui.widgets.WidgetBarra
import br.psascan.painel.ui.widgets.WidgetEstado
import br.psascan.painel.ui.widgets.WidgetFalhas
import br.psascan.painel.ui.widgets.WidgetLuz
import br.psascan.painel.ui.widgets.WidgetMostrador
import br.psascan.painel.ui.widgets.WidgetNumero
import br.psascan.painel.ui.widgets.WidgetNumeroHistorico
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale

/*
 * Interface comum dos widgets da tela personalizável (docs/LAYOUT.md, "Interface comum de widget"), a mesma de
 * psascan/static/widgets/base.js. Cada tipo é um arquivo em ui/widgets/ com um `object : TipoWidget`, registrado
 * em [Widgets.porTipo]. O widget só desenha: recebe um [Contexto] pronto, não fala com a central, não lê arquivo
 * (as imagens e fontes da pele vêm pelo helper de ArquivosTema.kt) e não altera o layout.
 * Cores, fontes e espessuras vêm SEMPRE de [Contexto.tema] (nenhum literal: há um teste que varre o código).
 */

/** O valor da última leitura: [numero] (null se não for número), [texto] (com a unidade, ou o texto do bit/enum), [bruto]. */
data class ValorLido(val numero: Double?, val texto: String, val bruto: Int?)

/** O estado do valor perante a faixa de referência (Fase 7). [faixa] = [min, max] normal agora; [condicao] = "em marcha lenta"… */
data class AvaliacaoWidget(
    val estado: Estado = Estado.NEUTRO, val lado: Lado? = null, val msg: String = "",
    val condicao: String = "", val faixa: List<Double>? = null,
) {
    /** Texto como no cabeçalho da Diagnóstico: NORMAL, ALTO, BAIXO, ATENÇÃO ou vazio. */
    val rotulo: String get() = when {
        estado == Estado.NEUTRO -> ""
        estado == Estado.NORMAL -> "NORMAL"
        lado == Lado.ACIMA -> "ALTO"
        lado == Lado.ABAIXO -> "BAIXO"
        else -> "ATENÇÃO"
    }
}

/** Outra métrica citada nas opções (`centro.metrica`, `arco_inferior.metrica`…) e o valor dela. */
data class ExtraWidget(val metrica: Metrica, val valor: ValorLido?)

/** A última leitura de falhas da central: presentes e memorizadas, e a hora ("HH:mm:ss"). */
data class FalhasLidas(val presentes: List<FalhaLida>, val memorizadas: List<FalhaLida>, val hora: String)

/** O espaço do widget: em dp (para o layout) e em pixels (para o desenho). */
data class AreaWidget(val largura: Dp, val altura: Dp, val larguraPx: Float, val alturaPx: Float)

/** As opções do layout já completadas com os padrões do catálogo; as que não têm padrão só existem se o layout as trouxe. */
class Opcoes(val json: JsonObject) {
    private fun prim(k: String) = json[k] as? JsonPrimitive
    fun bool(k: String): Boolean? = prim(k)?.takeIf { !it.isString }?.booleanOrNull
    fun inteiro(k: String): Int? = prim(k)?.takeIf { !it.isString }?.intOrNull
    fun numero(k: String): Double? = prim(k)?.takeIf { !it.isString }?.doubleOrNull
    fun texto(k: String): String? = prim(k)?.takeIf { it.isString }?.content
    fun objeto(k: String): Opcoes? = (json[k] as? JsonObject)?.let(::Opcoes)
    operator fun contains(k: String) = k in json
}

/**
 * O que um widget recebe, montado pela [Grade] a cada leitura (os mesmos campos do `c` da web).
 *
 * - [metrica]: a métrica do perfil (nome, curto, unidade, tipo, decimais, escala, valores…); null para widgets
 *   sem métrica (`falhas`, `luz` com `fonte: falhas`).
 * - [valor]: a última leitura, ou null (sem leitura ainda / o bloco dela não foi lido).
 * - [historico]: lista (segundos, número) dos últimos `opcoes.segundos` (60 se a opção não existe); [agora] é o
 *   instante "agora" na mesma escala de segundos. Só é calculado se o widget ler.
 * - [avaliacao]: a faixa (normal/atenção/fora) agora; neutra se não houver regra ativa.
 * - [extra]: valores das outras métricas citadas nas opções, por id.
 * - [falhas]: a última leitura de falhas, ou null se ainda não foi lida.
 * - [tema]: o `painel` do tema ativo, com as peles (`tema.peles.<tipo>`).
 * - [temaId]: id do tema ativo (para achar as imagens e fontes da pele: ver ArquivosTema).
 * - [area]: o tamanho do espaço do widget.
 * - [opcoes]: as opções do layout completadas com os padrões do catálogo.
 * - [titulo]: o título a mostrar (o do layout, ou o nome curto da métrica; vazio se não houver).
 */
data class Contexto(
    val metrica: Metrica?,
    val valor: ValorLido?,
    val avaliacao: AvaliacaoWidget,
    val extra: Map<String, ExtraWidget>,
    val falhas: FalhasLidas?,
    val tema: Painel,
    val temaId: String,
    val area: AreaWidget,
    val opcoes: Opcoes,
    val titulo: String,
    val agora: Double = 0.0,
    private val historicoDe: (Double) -> List<Pair<Double, Double>> = { emptyList() },
) {
    val historico: List<Pair<Double, Double>> get() = historicoDe(opcoes.inteiro("segundos")?.toDouble() ?: 60.0)
}

/** Um tipo de widget do catálogo (`tipo` = a chave em assets/widgets/catalogo.json). */
interface TipoWidget {
    val tipo: String

    /** Desenha o widget ocupando [modifier] (já com o tamanho do espaço); é chamado a cada leitura. */
    @Composable
    fun Desenhar(c: Contexto, modifier: Modifier)
}

/** Os tipos implementados. Tipo do catálogo que não está aqui aparece como "módulo ausente: <tipo>". */
object Widgets {
    val porTipo: Map<String, TipoWidget> = listOf<TipoWidget>(WidgetNumero, WidgetEstado, WidgetLuz, WidgetFalhas, WidgetNumeroHistorico, WidgetBarra, WidgetMostrador)
        .associateBy { it.tipo }
}

// ---------------------------------------------------------------------------------------------
// Ajudas comuns dos widgets

/** Cor do estado da faixa, pelas cores do tema. */
fun corEstado(c: Contexto, estado: Estado = c.avaliacao.estado): Color {
    val k = c.tema.cores
    return when (estado) { Estado.NORMAL -> k.ok; Estado.ATENCAO -> k.ambar; Estado.FORA -> k.alerta; Estado.NEUTRO -> k.texto }
}

/** Número no jeito brasileiro com [dec] casas; "—" se não houver. */
fun numBr(x: Double?, dec: Int = 0): String =
    if (x == null || x.isNaN()) "—" else String.format(Locale.ROOT, "%.${dec}f", x).replace('.', ',')

/** Casas decimais da métrica (a opção do layout tem prioridade). */
fun decimais(c: Contexto): Int = c.opcoes.inteiro("decimais") ?: c.metrica?.decimais ?: 0

/** Tamanho de fonte: o desenho é calculado em dp (como o px da web); isto o converte sem depender da escala de fonte do aparelho. */
@Composable
fun Float.dpSp(): TextUnit = with(LocalDensity.current) { this@dpSp.dp.toSp() }
