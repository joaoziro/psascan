package br.psascan.painel

import br.psascan.painel.protocolo.Perfil
import br.psascan.painel.ui.layout.Catalogo
import br.psascan.painel.ui.layout.EditorLayout
import br.psascan.painel.ui.layout.Geometria
import br.psascan.painel.ui.layout.GradeLayout
import br.psascan.painel.ui.layout.Layout
import br.psascan.painel.ui.layout.Ret
import br.psascan.painel.ui.layout.ValidadorLayout
import br.psascan.painel.ui.layout.WidgetLayout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Encaixe do modo edição (mover, redimensionar, sobreposição, limites do catálogo) e as operações sobre o layout. */
class EdicaoTest {
    private val catalogo = Catalogo.de(File("src/main/assets/widgets/catalogo.json").readText())
    private val perfil = Perfil.de(File("src/main/assets/perfil.json").readText())
    private val metricas = perfil.metricas
    private val grade = GradeLayout(12, 6)

    private fun w(id: String, x: Int, y: Int, l: Int, a: Int, tipo: String = "numero", metrica: String? = "rpm") =
        WidgetLayout(id, tipo, metrica, x, y, l, a)

    private fun layout(vararg ws: WidgetLayout) = Layout(1, "teste", "Teste", null, grade, null, ws.toList())

    @Test fun arrastoArredondaParaACelulaMaisProxima() {
        assertEquals(0, Geometria.celulas(29f, 60f))
        assertEquals(1, Geometria.celulas(31f, 60f))
        assertEquals(-1, Geometria.celulas(-31f, 60f))
        assertEquals(2, Geometria.celulas(120f, 60f))
        assertEquals(0, Geometria.celulas(500f, 0f))
    }

    @Test fun moverFicaDentroDaGrade() {
        val o = Ret(2, 1, 3, 2)
        assertEquals(Ret(3, 2, 3, 2), Geometria.mover(o, 1, 1, grade))
        assertEquals(Ret(0, 0, 3, 2), Geometria.mover(o, -9, -9, grade))
        assertEquals(Ret(9, 4, 3, 2), Geometria.mover(o, 99, 99, grade))
    }

    @Test fun cabeNaoDeixaSairNemSobrepor() {
        val ws = listOf(w("a", 0, 0, 2, 2), w("b", 4, 0, 2, 2))
        assertTrue(Geometria.cabe(Ret(2, 0, 2, 2), grade, ws))
        assertFalse("sobrepõe a", Geometria.cabe(Ret(1, 1, 2, 2), grade, ws))
        assertFalse("sobrepõe b pela borda", Geometria.cabe(Ret(3, 0, 2, 1), grade, ws))
        assertTrue("o próprio widget não conta", Geometria.cabe(Ret(1, 1, 2, 2), grade, ws, ignorar = "a"))
        assertFalse("sai pela direita", Geometria.cabe(Ret(11, 0, 2, 1), grade, ws))
        assertFalse("sai por baixo", Geometria.cabe(Ret(0, 5, 1, 2), grade, ws))
        assertFalse("negativo", Geometria.cabe(Ret(-1, 0, 1, 1), grade, ws))
        assertFalse("vazio", Geometria.cabe(Ret(0, 3, 0, 1), grade, ws))
    }

    @Test fun redimensionarRespeitaMinimoMaximoEGrade() {
        val num = catalogo.tipos.getValue("numero")          // 1×1 a 6×4
        val o = Ret(0, 0, 2, 2)
        assertEquals(Ret(0, 0, 4, 3), Geometria.redimensionar(o, 2, 1, num, grade))
        assertEquals("não passa do mínimo", Ret(0, 0, 1, 1), Geometria.redimensionar(o, -5, -5, num, grade))
        assertEquals("não passa do máximo", Ret(0, 0, 6, 4), Geometria.redimensionar(o, 50, 50, num, grade))
        val perto = Ret(10, 4, 1, 1)
        assertEquals("não sai da grade", Ret(10, 4, 2, 2), Geometria.redimensionar(perto, 9, 9, num, grade))
        val mostrador = catalogo.tipos.getValue("mostrador")  // mín. 2×2
        assertEquals(Ret(0, 0, 2, 2), Geometria.redimensionar(Ret(0, 0, 4, 4), -9, -9, mostrador, grade))
    }

    @Test fun redimensionarQueSobrepoeNaoCabe() {
        val ws = listOf(w("a", 0, 0, 2, 2), w("b", 3, 0, 2, 2))
        val alvo = Geometria.redimensionar(Ret(0, 0, 2, 2), 2, 0, catalogo.tipos.getValue("numero"), grade)
        assertEquals(Ret(0, 0, 4, 2), alvo)
        assertFalse(Geometria.cabe(alvo, grade, ws, "a"))
        assertTrue(Geometria.cabe(Geometria.redimensionar(Ret(0, 0, 2, 2), 1, 0, catalogo.tipos.getValue("numero"), grade), grade, ws, "a"))
    }

    @Test fun primeiroLivreVarreLinhaALinha() {
        val ws = listOf(w("a", 0, 0, 12, 1), w("b", 0, 1, 3, 2))
        assertEquals(3 to 1, Geometria.primeiroLivre(2, 2, grade, ws))
        assertEquals(0 to 3, Geometria.primeiroLivre(12, 3, grade, ws))
        assertNull(Geometria.primeiroLivre(12, 6, grade, ws))
    }

    @Test fun adicionarPoeUmNumeroNoPrimeiroLugarLivre() {
        val l = layout(w("numero-1", 0, 0, 2, 2))
        val n = EditorLayout.adicionar(l, catalogo, metricas)!!
        val novo = n.widgets.last()
        assertEquals("numero-2", novo.id); assertEquals("numero", novo.tipo)
        assertEquals(2, novo.x); assertEquals(0, novo.y); assertEquals(2, novo.largura)
        assertNotNull(novo.metrica)
        ValidadorLayout(catalogo).validar(n)   // o resultado é um layout válido
        // sem espaço para 2×2 mas com 1×1
        val cheio = Layout(1, "t", "T", null, GradeLayout(4, 3), null,
            (0 until 3).flatMap { y -> (0 until 4).map { x -> w("c$x$y", x, y, 1, 1) } }.filterNot { it.id == "c33" }.dropLast(1))
        assertEquals(1, EditorLayout.adicionar(cheio, catalogo, metricas)!!.widgets.last().largura)
        val lotado = cheio.copy(widgets = (0 until 3).flatMap { y -> (0 until 4).map { x -> w("c$x$y", x, y, 1, 1) } })
        assertNull(EditorLayout.adicionar(lotado, catalogo, metricas))
    }

    @Test fun trocarTipoAjustaTamanhoMetricaEOpcoes() {
        val comOpcoes = w("a", 0, 0, 3, 3).copy(opcoes = buildJsonObject { put("decimais", 1); put("mostrar_unidade", false) })
        val l = layout(comOpcoes)
        val m = EditorLayout.trocarTipo(l, catalogo, "a", "estado", metricas)!!.widgets.single()
        assertEquals("estado", m.tipo)
        assertEquals("o estado vai até 6×3", 3 to 3, m.largura to m.altura)
        assertEquals("estado não tem opções", JsonObject(emptyMap()), m.opcoes)
        // luz só aceita bit: a métrica rpm é trocada pela primeira de bit
        val luz = EditorLayout.trocarTipo(l, catalogo, "a", "luz", metricas)!!.widgets.single()
        assertEquals(3 to 3, luz.largura to luz.altura)
        assertTrue(perfil.metricaPorId(luz.metrica!!)!!.tipo == "bit")
        // falhas não usa métrica
        assertNull(EditorLayout.trocarTipo(l, catalogo, "a", "falhas", metricas)!!.widgets.single().metrica)
        // mostrador exige 2×2: não cabe ao lado de outro
        val apertado = layout(w("a", 0, 0, 1, 1), w("b", 1, 0, 1, 1), w("c", 0, 1, 1, 1))
        assertNull(EditorLayout.trocarTipo(apertado, catalogo, "a", "mostrador", metricas))
        ValidadorLayout(catalogo).validar(EditorLayout.trocarTipo(l, catalogo, "a", "mostrador", metricas)!!)
    }

    @Test fun opcoesPorCaminhoApagamObjetosVazios() {
        val vazio = JsonObject(emptyMap())
        val a = EditorLayout.definirOpcao(vazio, listOf("escala", "min"), JsonPrimitive(100))
        assertEquals(100, (a["escala"] as JsonObject)["min"]!!.let { (it as JsonPrimitive).content.toInt() })
        val b = EditorLayout.definirOpcao(a, listOf("escala", "min"), null)
        assertEquals("o objeto que ficou vazio some junto", vazio, b)
        assertEquals(JsonPrimitive(100), EditorLayout.valorOpcao(a, listOf("escala", "min")))
        assertNull(EditorLayout.valorOpcao(a, listOf("escala", "max")))
    }

    @Test fun tamanhoDaGrade() {
        val l = layout(w("a", 8, 4, 2, 2))
        assertNull("widget ficaria de fora", EditorLayout.tamanhoGrade(l, colunas = true, valor = 9))
        assertEquals(10, EditorLayout.tamanhoGrade(l, true, 10)!!.grade.colunas)
        assertNull("abaixo do mínimo de 4", EditorLayout.tamanhoGrade(layout(), true, 3))
        assertNull("acima do máximo de 12", EditorLayout.tamanhoGrade(layout(), false, 13))
        assertEquals(16, EditorLayout.tamanhoGrade(l, true, 16)!!.grade.colunas)
    }

    @Test fun moverEGeometriaViramLayoutValido() {
        val l = layout(w("a", 0, 0, 2, 2), w("b", 4, 0, 2, 2))
        val alvo = Geometria.mover(Ret.de(l.widgets[0]), 2, 3, grade)
        assertTrue(Geometria.cabe(alvo, grade, l.widgets, "a"))
        val n = EditorLayout.geometria(l, "a", alvo)
        assertEquals(2, n.widgets[0].x); assertEquals(3, n.widgets[0].y)
        assertEquals("o outro não muda", l.widgets[1], n.widgets[1])
        ValidadorLayout(catalogo).validar(n)
    }
}
