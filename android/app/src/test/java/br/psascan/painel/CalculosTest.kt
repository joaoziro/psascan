package br.psascan.painel

import br.psascan.painel.ui.layout.Opcoes
import br.psascan.painel.ui.widgets.CorSegmento
import br.psascan.painel.ui.widgets.NivelArcoInferior
import br.psascan.painel.ui.widgets.NivelTraco
import br.psascan.painel.ui.widgets.anguloDe
import br.psascan.painel.ui.widgets.digitosDosRolos
import br.psascan.painel.ui.widgets.escalaEfetiva
import br.psascan.painel.ui.widgets.fmtRotulo
import br.psascan.painel.ui.widgets.frac
import br.psascan.painel.ui.widgets.limitesDaBarra
import br.psascan.painel.ui.widgets.limitesDoGrafico
import br.psascan.painel.ui.widgets.medidasDosNumeros
import br.psascan.painel.ui.widgets.nivelDe
import br.psascan.painel.ui.widgets.nivelDoArcoInferior
import br.psascan.painel.ui.widgets.passoAuto
import br.psascan.painel.ui.widgets.passoDoEixoDeTempo
import br.psascan.painel.ui.widgets.passouPeloPonteiro
import br.psascan.painel.ui.widgets.segmentosAcesos
import br.psascan.painel.ui.widgets.segmentosDoArco
import br.psascan.painel.ui.widgets.trechosDoArco
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** As contas puras dos widgets mostrador, barra e numero_historico (as mesmas dos .js da web). */
class CalculosTest {
    private fun op(json: String) = Opcoes(Json.parseToJsonElement(json) as JsonObject)

    @Test fun passoAutoEscolheUmPassoBonito() {
        assertEquals(20.0, passoAuto(100.0), 1e-9)
        assertEquals(1000.0, passoAuto(7000.0), 1e-9)
        assertEquals(25.0, passoAuto(200.0), 1e-9)
        assertEquals(0.25, passoAuto(2.0), 1e-9)
    }

    @Test fun escalaVemDasOpcoes() {
        val e = escalaEfetiva(op("""{"escala":{"min":0,"max":7000,"maior":1000,"divisor_rotulo":1000,"alerta":6500}}"""), 0.0, 6000.0)
        assertEquals(7000.0, e.max, 0.0); assertEquals(1000.0, e.maior, 0.0); assertEquals(10, e.menor)
        assertEquals(1000.0, e.div, 0.0); assertEquals(6500.0, e.alerta!!, 0.0)
        assertEquals(70, e.total); assertEquals(7000.0, e.valorDe(70), 1e-9); assertEquals(100.0, e.valorDe(1), 1e-9)
    }

    @Test fun escalaFaltandoUsaAMetricaEOsPassosCalculados() {
        val e = escalaEfetiva(op("{}"), 0.0, 6000.0)
        assertEquals(6000.0, e.max, 0.0); assertEquals(1000.0, e.maior, 0.0); assertNull(e.alerta); assertEquals(1.0, e.div, 0.0)
        val semNada = escalaEfetiva(op("{}"), null, null)
        assertEquals(0.0, semNada.min, 0.0); assertEquals(100.0, semNada.max, 0.0); assertEquals(20.0, semNada.maior, 0.0)
    }

    @Test fun escalaInvalidaEhCorrigida() {
        assertEquals(11.0, escalaEfetiva(op("""{"escala":{"min":10,"max":5}}"""), null, null).max, 0.0)   // max <= min
        assertEquals(20, escalaEfetiva(op("""{"escala":{"menor":99}}"""), null, null).menor)             // 1 a 20
        assertEquals(1, escalaEfetiva(op("""{"escala":{"menor":0}}"""), null, null).menor)
        // régua fina não passa de ~400 traços: reduz as divisões
        val e = escalaEfetiva(op("""{"escala":{"min":0,"max":4000,"maior":100,"menor":20}}"""), null, null)
        assertTrue(e.total <= 400); assertTrue(e.menor < 20)
    }

    @Test fun rotulosEmPortugues() {
        assertEquals("7", fmtRotulo(7.0)); assertEquals("2,5", fmtRotulo(2.5)); assertEquals("0,13", fmtRotulo(0.125))
        assertEquals("1500", fmtRotulo(1500.0))
    }

    @Test fun niveisDosTracos() {
        assertEquals(NivelTraco.GRANDE, nivelDe(0, 10)); assertEquals(NivelTraco.GRANDE, nivelDe(10, 10))
        assertEquals(NivelTraco.MEDIO, nivelDe(5, 10)); assertEquals(NivelTraco.FINO, nivelDe(3, 10))
        assertEquals(NivelTraco.GRANDE, nivelDe(5, 5)); assertEquals(NivelTraco.FINO, nivelDe(2, 5))   // ímpar: sem médio
        assertEquals(NivelTraco.GRANDE, nivelDe(3, 1))
    }

    @Test fun tamanhoDosNumerosPeloMaiorRotulo() {
        val giros = escalaEfetiva(op("""{"escala":{"max":7000,"maior":1000,"divisor_rotulo":1000}}"""), null, null)
        assertEquals(60f to 16f, medidasDosNumeros(giros))
        val vel = escalaEfetiva(op("""{"escala":{"max":200,"maior":20}}"""), null, null)
        assertEquals(58.5f to 12.5f, medidasDosNumeros(vel))
        val longo = escalaEfetiva(op("""{"escala":{"max":7000,"maior":1000}}"""), null, null)
        assertEquals(58.5f to 10.5f, medidasDosNumeros(longo))
    }

    @Test fun angulos() {
        assertEquals(-128f, anguloDe(0.0, 0.0, 200.0, -128f, 128f), 1e-4f)
        assertEquals(0f, anguloDe(100.0, 0.0, 200.0, -128f, 128f), 1e-4f)
        assertEquals(128f, anguloDe(200.0, 0.0, 200.0, -128f, 128f), 1e-4f)
        assertEquals(60f, anguloDe(0.5, 0.0, 1.0, -90f, 210f), 1e-4f)   // pele com arco aberto: -90 a 210
    }

    @Test fun fracaoLimitada() {
        assertEquals(0.0, frac(-5.0, 0.0, 10.0), 0.0); assertEquals(1.0, frac(50.0, 0.0, 10.0), 0.0)
        assertEquals(.25, frac(2.5, 0.0, 10.0), 1e-12); assertEquals(0.0, frac(1.0, 1.0, 1.0), 0.0)
    }

    @Test fun marcasPassadasPeloPonteiro() {
        assertTrue(passouPeloPonteiro(1000.0, 3000.0, 0.0)); assertTrue(passouPeloPonteiro(3000.0, 3000.0, 0.0))
        assertFalse(passouPeloPonteiro(4000.0, 3000.0, 0.0)); assertFalse(passouPeloPonteiro(0.0, 0.0, 0.0))   // parado no mínimo: nada pintado
    }

    @Test fun arcoContinuoDaPele() {
        val meio = trechosDoArco(.5, -128f, 128f, null)
        assertEquals(-128f, meio.aceso!!.start, 1e-4f); assertEquals(0f, meio.aceso!!.endInclusive, 1e-4f); assertNull(meio.alerta)
        assertNull(trechosDoArco(0.0, -128f, 128f, 100f).aceso)
        val alto = trechosDoArco(.9, -128f, 128f, 100f)   // gv = 102,4: passou do alerta (100)
        assertEquals(100f, alto.aceso!!.endInclusive, 1e-4f); assertEquals(100f, alto.alerta!!.start, 1e-4f)
        assertEquals(102.4f, alto.alerta!!.endInclusive, 1e-3f)
        val cedo = trechosDoArco(.5, -128f, 128f, 100f)
        assertEquals(0f, cedo.aceso!!.endInclusive, 1e-4f); assertNull(cedo.alerta)
    }

    @Test fun segmentosAcesosDoArco() {
        assertEquals(listOf(CorSegmento.NORMAL, CorSegmento.NORMAL, CorSegmento.APAGADO, CorSegmento.APAGADO),
            segmentosDoArco(4, .5, 0.0, 100.0, null))
        assertTrue(segmentosDoArco(4, 0.0, 0.0, 100.0, null).all { it == CorSegmento.APAGADO })
        // centros em 12,5 / 37,5 / 62,5 / 87,5 %; alerta em 70
        assertEquals(listOf(CorSegmento.NORMAL, CorSegmento.NORMAL, CorSegmento.NORMAL, CorSegmento.ALERTA),
            segmentosDoArco(4, 1.0, 0.0, 100.0, 70.0))
    }

    @Test fun corDoArcoInferiorPorFaixa() {
        assertEquals(NivelArcoInferior.SEM_VALOR, nivelDoArcoInferior(null))
        val agua = { t: Double -> nivelDoArcoInferior(frac(t, 50.0, 130.0)) }   // o arco da água: 50 a 130 °C
        assertEquals(NivelArcoInferior.BAIXO, agua(60.0)); assertEquals(NivelArcoInferior.MEIO, agua(70.0))
        assertEquals(NivelArcoInferior.MEIO, agua(90.0)); assertEquals(NivelArcoInferior.MEIO, agua(109.0))
        assertEquals(NivelArcoInferior.ALTO, agua(110.0)); assertEquals(NivelArcoInferior.ALTO, agua(128.0))
    }

    @Test fun rolosDeOdometro() {
        assertEquals("0860", digitosDosRolos(856.0, 7000.0)); assertEquals("1230", digitosDosRolos(1234.0, 7000.0))
        assertEquals("0056", digitosDosRolos(56.4, 200.0)); assertEquals("----", digitosDosRolos(null, 7000.0))
        assertEquals("0000", digitosDosRolos(-5.0, 200.0)); assertEquals("9999", digitosDosRolos(19999.0, 200.0))
    }

    @Test fun segmentosDaBarra() {
        assertEquals(10, segmentosAcesos(.5, 20)); assertEquals(0, segmentosAcesos(0.0, 20)); assertEquals(20, segmentosAcesos(1.0, 20))
        assertEquals(1, segmentosAcesos(.026, 20))
    }

    @Test fun limitesDaBarraNuncaIguais() {
        assertEquals(0.0 to 100.0, limitesDaBarra(op("{}"), null, null))
        assertEquals(0.0 to 16.0, limitesDaBarra(op("{}"), 0.0, 16.0))
        assertEquals(10.0 to 20.0, limitesDaBarra(op("""{"min":10,"max":20}"""), 0.0, 16.0))
        assertEquals(5.0 to 6.0, limitesDaBarra(op("""{"min":5,"max":5}"""), null, null))
    }

    @Test fun limitesDoGraficoComMargem() {
        val (lo, hi) = limitesDoGrafico(listOf(10.0, 20.0), null)
        assertEquals(8.8, lo, 1e-9); assertEquals(21.2, hi, 1e-9)
        val (l2, h2) = limitesDoGrafico(listOf(10.0, 12.0), listOf(8.0, 16.0))   // a faixa entra na conta
        assertEquals(8.0 - 8 * .12, l2, 1e-9); assertEquals(16.0 + 8 * .12, h2, 1e-9)
        assertEquals(-0.12, limitesDoGrafico(emptyList(), null).first, 1e-9)
        val (l3, h3) = limitesDoGrafico(listOf(5.0, 5.0), null)   // constante: abre uma janela
        assertTrue(l3 < 5.0 && h3 > 5.0)
        assertNotNull(op("{}"))
    }

    @Test fun passoDoEixoDeTempoPelaJanela() {
        assertEquals(5, passoDoEixoDeTempo(30)); assertEquals(10, passoDoEixoDeTempo(60)); assertEquals(30, passoDoEixoDeTempo(120))
        assertEquals(60, passoDoEixoDeTempo(600))
    }
}
