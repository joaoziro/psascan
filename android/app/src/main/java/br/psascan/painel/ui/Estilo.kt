package br.psascan.painel.ui

import androidx.compose.ui.text.font.FontFamily

/** Fonte do painel: a do tema ativo (no Padrão, a condensada do sistema, no espírito dos painéis dos anos 90). */
val FONTE_PAINEL: FontFamily get() = Temas.familia

/** Cores do tema ativo (painel.cores). Os nomes são os de uso na tela; os valores vêm do tema. */
object Cor {
    private val c get() = Temas.painel.cores
    val passou get() = c.passou        // o que o ponteiro já passou; também seleção e atenção
    val ambar get() = c.ambar
    val grande get() = c.texto
    val medio get() = c.textoMedio
    val fino get() = c.textoFino
    val vermelho get() = c.alerta
    val verde get() = c.ok
    val apagado get() = c.textoApagado
    val fundo get() = c.fundo
    val superficie get() = c.superficie
    val superficie2 get() = c.superficie2
    val borda get() = c.borda
    val campo get() = c.campo
}
