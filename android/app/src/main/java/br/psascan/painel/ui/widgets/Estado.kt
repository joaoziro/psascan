package br.psascan.painel.ui.widgets

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.psascan.painel.protocolo.Estado
import br.psascan.painel.ui.layout.Contexto
import br.psascan.painel.ui.layout.TipoWidget
import br.psascan.painel.ui.layout.corEstado
import br.psascan.painel.ui.layout.dpSp
import br.psascan.painel.ui.layout.rememberFontePele
import kotlin.math.max
import kotlin.math.min

/**
 * Widget `estado`: rótulo + estado em texto grande (NORMAL / ALTO / BAIXO / ATENÇÃO, ou o texto da métrica discreta);
 * o mesmo de estado.js. Pele `estado` (docs/TEMA.md): fonte (`fonte_app`) e `caixa` (contorno em volta do texto).
 */
object WidgetEstado : TipoWidget {
    override val tipo = "estado"

    @Composable
    override fun Desenhar(c: Contexto, modifier: Modifier) {
        val w = c.area.largura.value
        val h = c.area.altura.value
        val k = c.tema.cores
        val pele = c.tema.peles.estado
        val familia = rememberFontePele(c.temaId, pele.fonteApp)
        val m = c.metrica
        val av = c.avaliacao
        val discreta = m != null && m.ehDiscreto

        var t = "—"
        var sub = ""
        var cor = k.texto
        var semFaixa = false
        c.valor?.let { v ->
            if (discreta) t = v.texto.ifEmpty { "—" }.uppercase()
            else {
                val r = av.rotulo
                if (r.isNotEmpty()) { t = r; cor = corEstado(c); sub = av.msg }
                else {   // sem regra para a condição de agora
                    t = "SEM FAIXA AGORA"; cor = k.textoApagado; sub = av.condicao; semFaixa = true
                }
                if (m?.id == "lambda" || (av.msg.isNotEmpty() && r.isEmpty() && av.estado != Estado.NEUTRO)) sub = av.msg.ifEmpty { sub }
            }
        }
        val disp = h - if (c.titulo.isNotEmpty()) h * 0.22f else 0f
        val fs = max(8f, min(min(disp * if (sub.isNotEmpty()) 0.42f else 0.6f, (w * 0.9f) / (max(t.length, 3) * 0.58f)),
            if (semFaixa) disp * 0.28f else 1e9f))
        val fsMsg = max(8f, min(min(fs * 0.45f, disp * 0.2f), (w * 0.92f) / max(sub.length * 0.4f, 6f)))
        val fsTitulo = max(8f, min(h * 0.17f, w * 0.12f))

        Column(modifier.clipToBounds(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((h * 0.04f).dp, Alignment.CenterVertically)) {
            if (c.titulo.isNotEmpty()) Text(c.titulo.uppercase(), color = k.textoApagado, fontFamily = familia, fontSize = fsTitulo.dpSp(),
                letterSpacing = (fsTitulo * 0.06f).dpSp(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val caixa = if (pele.caixa) Modifier.border(max(1f, h * 0.025f).dp, cor, RoundedCornerShape(c.tema.raios.rotulo.dp))
                .padding(horizontal = (w * 0.05f).dp, vertical = (h * 0.04f).dp) else Modifier
            Column(caixa, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t, color = cor, fontFamily = familia, fontWeight = FontWeight.Bold, fontSize = fs.dpSp(), lineHeight = (fs * 1.05f).dpSp(),
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (sub.isNotEmpty()) Text(sub, color = k.textoMedio, fontFamily = familia, fontSize = fsMsg.dpSp(),
                    lineHeight = (fsMsg * 1.15f).dpSp(), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}
