package br.psascan.painel

import br.psascan.painel.ui.layout.Catalogo
import br.psascan.painel.ui.layout.Layout
import br.psascan.painel.ui.layout.LayoutInvalido
import br.psascan.painel.ui.layout.ValidadorLayout
import br.psascan.painel.ui.layout.paraJson
import br.psascan.painel.ui.layout.usaFalhas
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** A validação dos layouts confere com a do Python (vetores.json → layouts.casos) e os prontos passam. */
class LayoutTest {
    private val catalogo = Catalogo.de(File("src/main/assets/widgets/catalogo.json").readText())
    private val validador = ValidadorLayout(catalogo)
    private val vetores = Json.parseToJsonElement(javaClass.classLoader!!.getResource("vetores.json").readText()).jsonObject

    @Test fun casosDoPython() {
        val casos = vetores["layouts"]!!.jsonObject["casos"]!!.jsonArray
        assertTrue("sem casos nos vetores", casos.size >= 30)
        for (e in casos) {
            val caso = e.jsonObject
            val nome = caso["nome"]!!.jsonPrimitive.content
            val json = caso["json"]!!.jsonPrimitive.content
            val valido = caso["valido"]!!.jsonPrimitive.boolean
            val contem = caso["motivo_contem"]!!.jsonPrimitive.content
            try {
                validador.carregar(json)
                if (!valido) fail("$nome: devia recusar")
            } catch (x: LayoutInvalido) {
                if (valido) fail("$nome: devia aceitar, mas recusou: ${x.motivo}")
                assertTrue("$nome: motivo '${x.motivo}' sem '$contem'", x.motivo.contains(contem, ignoreCase = true))
            }
        }
    }

    @Test fun osSeisProntosSaoValidos() {
        val arquivos = File("src/main/assets/layouts").listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertEquals(listOf("completo", "giro-grande", "marcha-lenta", "mistura-lambda", "painel", "partida-quente"),
            arquivos.map { it.name.removeSuffix(".json") })
        for (a in arquivos) {
            val l = validador.carregar(a.readBytes())
            assertEquals(a.name.removeSuffix(".json"), l.id)
            assertTrue(l.widgets.isNotEmpty())
        }
    }

    @Test fun mensagensTraemOCaminhoDoCampo() {
        val base = """{"formato":1,"id":"teste","nome":"T","grade":{"colunas":12,"linhas":6},"widgets":[%s]}"""
        fun motivo(widgets: String): String = try { validador.carregar(base.format(widgets)); "" } catch (e: LayoutInvalido) { e.motivo }
        assertEquals("widgets[1].largura: o widget \"numero\" aceita de 1 a 6 células",
            motivo("""{"id":"a","tipo":"numero","metrica":"rpm","x":0,"y":0,"largura":1,"altura":1},{"id":"b","tipo":"numero","metrica":"rpm","x":1,"y":0,"largura":7,"altura":1}"""))
        assertEquals("widgets[0].opcoes.decimais: fora da faixa (máximo 4)",
            motivo("""{"id":"a","tipo":"numero","metrica":"rpm","x":0,"y":0,"largura":2,"altura":2,"opcoes":{"decimais":9}}"""))
        assertEquals("widgets[1]: sobrepõe o widget \"a\"",
            motivo("""{"id":"a","tipo":"numero","metrica":"rpm","x":0,"y":0,"largura":2,"altura":2},{"id":"b","tipo":"numero","metrica":"rpm","x":1,"y":1,"largura":2,"altura":2}"""))
    }

    @Test fun jsonInvalidoEGrandeDemais() {
        try { validador.carregar("{nao é json"); fail() } catch (e: LayoutInvalido) { assertTrue(e.motivo.startsWith("JSON inválido")) }
        try { validador.carregar(ByteArray(300 * 1024) { ' '.code.toByte() }); fail() } catch (e: LayoutInvalido) { assertTrue(e.motivo.contains("grande demais")) }
        try { validador.carregar("[1]"); fail() } catch (e: LayoutInvalido) { assertTrue(e.motivo.contains("objeto")) }
    }

    @Test fun completaOpcoesComOsPadroesDoCatalogo() {
        val l = validador.carregar(File("src/main/assets/layouts/painel.json").readText())
        val giros = l.widgets.first { it.id == "giros" }
        val op = catalogo.completarOpcoes(giros.tipo, giros.opcoes)
        assertEquals(10, op["escala"]!!.jsonObject["menor"]!!.jsonPrimitive.int)       // padrão do catálogo
        assertEquals(7000, op["escala"]!!.jsonObject["max"]!!.jsonPrimitive.int)       // do layout
        assertEquals("rolos", op["centro"]!!.jsonObject["conteudo"]!!.jsonPrimitive.content)
        assertNull("arco_inferior só existe se o layout pediu", op["arco_inferior"])
        val num = l.widgets.first { it.tipo == "numero" }
        val opn = catalogo.completarOpcoes(num.tipo, num.opcoes)
        assertEquals(JsonPrimitive(true), opn["mostrar_unidade"])
        assertEquals(JsonPrimitive(true), opn["mostrar_estado"])
        assertNull("decimais não tem padrão (vem do perfil)", opn["decimais"])
    }

    @Test fun metricasCitadasNasOpcoes() {
        val l = validador.carregar(File("src/main/assets/layouts/painel.json").readText())
        val vel = l.widgets.first { it.id == "vel" }
        assertEquals(listOf("arco_inferior.metrica" to "agua"), catalogo.metricasDasOpcoes(vel.tipo, vel.opcoes))
    }

    @Test fun categoriasDeMetrica() {
        assertTrue(catalogo.aceita("numero", "u8"))
        assertTrue(catalogo.aceita("numero", "enum"))
        assertTrue("bit conta como discreta", catalogo.aceita("numero", "bit"))
        assertFalse(catalogo.aceita("mostrador", "bit"))
        assertTrue(catalogo.aceita("luz", "bit"))
        assertFalse(catalogo.aceita("luz", "u8"))
        assertFalse(catalogo.aceita("falhas", "u8"))
    }

    @Test fun gravaERelêIgual() {
        for (a in File("src/main/assets/layouts").listFiles { f -> f.name.endsWith(".json") }!!) {
            val l = validador.carregar(a.readText())
            val volta = validador.carregar(l.paraJson())
            assertEquals(a.name, l, volta)
        }
    }

    @Test fun usaFalhas() {
        val completo = validador.carregar(File("src/main/assets/layouts/completo.json").readText())
        val painel = validador.carregar(File("src/main/assets/layouts/painel.json").readText())
        assertTrue(completo.widgets.any { it.tipo == "falhas" } == completo.usaFalhas)
        assertFalse(painel.widgets.any { it.tipo == "falhas" || (it.opcoes["fonte"] as? JsonPrimitive)?.content == "falhas" } != painel.usaFalhas)
        assertNotNull(painel.grade)
    }
}
