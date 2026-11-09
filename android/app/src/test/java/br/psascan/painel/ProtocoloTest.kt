package br.psascan.painel

import br.psascan.painel.protocolo.AnaliseLambda
import br.psascan.painel.protocolo.Dongle
import br.psascan.painel.protocolo.EscritaBloqueada
import br.psascan.painel.protocolo.Faixas
import br.psascan.painel.protocolo.Perfil
import br.psascan.painel.protocolo.PerfilInvalido
import br.psascan.painel.protocolo.Psa5
import br.psascan.painel.protocolo.Sagem
import br.psascan.painel.protocolo.Scanner
import br.psascan.painel.protocolo.SimTransporte
import br.psascan.painel.protocolo.bytesHex
import br.psascan.painel.protocolo.hex
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Confere o Kotlin contra android/app/src/test/resources/vetores.json, calculado pela versão Python. */
class ProtocoloTest {
    private val textoPerfil = File("src/main/assets/perfil.json").readText()
    private val perfil = Perfil.de(textoPerfil)
    private val v: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("vetores.json").readText()).jsonObject

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test fun crcX25() {
        assertArrayEquals(hex(v["crc_123456789"]!!.jsonPrimitive.content), Dongle.crc16X25("123456789".toByteArray()))
    }

    @Test fun quadrosIguaisAoPython() {
        for (q in v["quadros"]!!.jsonArray) {
            val p = hex(q.jsonObject["payload"]!!.jsonPrimitive.content)
            val esperado = hex(q.jsonObject["quadro"]!!.jsonPrimitive.content)
            assertArrayEquals(esperado, Dongle.frame(p))
            assertArrayEquals(p, Dongle.unframe(esperado).first)
        }
    }

    @Test fun quadroDeInicioDaFamilia() {
        assertArrayEquals(hex("820f6e"), Sagem.quadro(perfil.conexao.inicio.bytesHex()))
        assertArrayEquals(byteArrayOf(0x0F), Sagem.desquadro(hex("820f6e"), base = 0x80))
    }

    @Test fun conversoesIguaisAoPython() {
        for (b in v["blocos"]!!.jsonArray) {
            val d = hex(b.jsonObject["dados"]!!.jsonPrimitive.content)
            for ((ordem, esp) in b.jsonObject["valores"]!!.jsonObject) {
                val r = perfil.converter(perfil.metrica(ordem.toInt()), d)
                val e = esp.jsonObject
                assertEquals("raw $ordem", e["raw"]!!.jsonPrimitive.intOrNull, r.raw)
                assertEquals("número $ordem", e["numero"]!!.jsonPrimitive.double, r.numero!!, 1e-6)
                assertEquals("texto $ordem", e["texto"]!!.jsonPrimitive.content, r.texto)
            }
        }
    }

    @Test fun falhasIguaisAoPython() {
        for (c in v["falhas"]!!.jsonArray) {
            val lst = Sagem.falhas(Sagem.desquadro(hex(c.jsonObject["resposta"]!!.jsonPrimitive.content)), 0x32)!!
            val esp = c.jsonObject["falhas"]!!.jsonArray.map {
                val o = it.jsonObject
                Sagem.Falha(o["codigo"]!!.jsonPrimitive.int, o["tipo"]!!.jsonPrimitive.int, o["presente"]!!.jsonPrimitive.boolean)
            }
            assertEquals(esp, lst)
        }
        assertNull(Sagem.falhas(byteArrayOf(0x32), 0x32))
    }

    @Test fun faixasIguaisAoPython() {
        for (c in v["faixas"]!!.jsonArray) {
            val o = c.jsonObject
            val vals = o["valores"]!!.jsonObject.entries.associate { (k, x) -> k.toInt() to x.jsonPrimitive.double }
            val ctx = Faixas.contexto(perfil, vals)
            assertEquals(o["contexto"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(), ctx)
            for ((k, e) in o["estados"]!!.jsonObject) {
                val a = Faixas.avaliar(perfil.regras(k.toInt()), vals[k.toInt()], ctx)
                assertEquals("estado $k", e.jsonPrimitive.content.uppercase(), a.estado.name)
                assertEquals("msg $k", o["msgs"]!!.jsonObject[k]!!.jsonPrimitive.content, a.msg)
                assertEquals("lado $k", o["lados"]!!.jsonObject[k]!!.jsonPrimitive.content, a.lado?.name?.lowercase() ?: "")
            }
        }
    }

    @Test fun lambdaIgualAoPython() {
        for (c in v["lambda"]!!.jsonArray) {
            val o = c.jsonObject
            val serie = o["serie"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.double to it.jsonArray[1].jsonPrimitive.double }
            val r = AnaliseLambda.resumo(serie)
            val e = o["resumo"]!!.jsonObject
            assertEquals(e["diagnostico"]!!.jsonPrimitive.content, r.diagnostico)
            assertEquals(e["trocas"]!!.jsonPrimitive.int, r.trocas)
            assertEquals(e["pct_rico"]!!.jsonPrimitive.int, r.pctRico)
        }
    }

    @Test fun conversaCompletaComOSimulador() {
        val e = Psa5(Scanner(SimTransporte(perfil)), perfil)
        val ver = e.conectar()
        assertEquals("1.2", ver.firmware)
        val vals = e.lerParametros().associateBy { it.m.id }
        assertEquals(perfil.metricas.size, vals.size)
        assertTrue("rpm ${vals["rpm"]!!.numero}", vals["rpm"]!!.numero!! in 700.0..1000.0)
        assertTrue("água ${vals["agua"]!!.numero}", vals["agua"]!!.numero!! in 30.0..60.0)    // começa frio
        assertEquals("marcha lenta", vals["pedal"]!!.texto)
        val falhas = e.lerFalhas()!!
        assertEquals(listOf(0x127, 0x2A, 0x29), falhas.map { it.codigo })
        assertEquals(listOf(true, false, false), falhas.map { it.presente })
    }

    @Test fun textosVemDaUnidadeEDosValoresDoPerfil() {
        val d = hex("11000c651a0000103b0973ba4d6cc000002c29000000000000")
        val t = { id: String -> perfil.converter(perfil.metricaPorId(id)!!, d).texto }
        assertEquals("832 rpm", t("rpm"))
        assertEquals("14.0 V", t("bateria"))
        assertEquals("marcha lenta", t("pedal"))
        assertEquals("não", t("rele_principal"))
        // valor fora da lista de valores aparece como "?"
        d[7] = 99
        assertEquals("?", t("pedal"))
    }

    @Test fun simuladorRespondeSoAosComandosDoPerfil() {
        val s = Scanner(SimTransporte(perfil))
        Psa5(s, perfil).conectar()
        val ecu = { pedido: String -> Sagem.desquadro(s.transact(Sagem.paraDongle(pedido.bytesHex())).let { it.copyOfRange(1, it.size) }) }
        assertEquals("c3", ecu("C3").hex())                 // C2 C3 7A: o eco do pedido
        assertEquals("66", ecu("B4 06").hex())
        assertEquals("7f", ecu("B4 17").hex())              // fora do perfil: recusado
    }

    // --- perfil: carregar e validar ---------------------------------------------------------------

    @Test fun perfilDaSl96Carrega() {
        assertEquals("sagem-sl96", perfil.id)
        assertEquals(21, perfil.metricas.size)
        assertEquals("lambda", perfil.lambda!!.id)
        assertEquals("luz_injecao", perfil.luzFalha!!.id)
        assertEquals("apagar_falhas", perfil.apagarFalhas!!.id)
        assertEquals(listOf(0, 1), perfil.blocos.map { it.id })
        assertEquals(0, perfil.blocoPrincipal)
    }

    private val raiz = Json.parseToJsonElement(textoPerfil).jsonObject
    private fun JsonObject.com(k: String, v: JsonElement) = JsonObject(this + (k to v))
    private fun JsonObject.sem(k: String) = JsonObject(this - k)
    private fun JsonObject.em(k: String, f: (JsonObject) -> JsonObject) = com(k, f(getValue(k).jsonObject))
    private fun JsonObject.metrica(i: Int, f: (JsonObject) -> JsonObject) =
        com("metricas", JsonArray(getValue("metricas").jsonArray.mapIndexed { j, m -> if (j == i) f(m.jsonObject) else m }))

    private fun invalido(parte: String, perfilRuim: JsonObject) {
        val e = assertThrows(PerfilInvalido::class.java) { Perfil.de(perfilRuim.toString()) }
        assertTrue("\"${e.message}\" deveria falar de $parte", e.message!!.contains(parte))
    }

    @Test fun perfilInvalidoDaErroComMotivo() {
        invalido("formato", raiz.com("formato", JsonPrimitive(2)))
        invalido("família", raiz.com("familia", JsonPrimitive("kwp")))
        invalido("conexao.pino", raiz.em("conexao") { it.com("pino", JsonPrimitive("13 7Z")) })
        invalido("leitura.blocos", raiz.em("leitura") { it.com("blocos", JsonArray(emptyList())) })
        invalido("bloco 9", raiz.metrica(0) { it.com("bloco", JsonPrimitive(9)) })
        invalido("tipo", raiz.metrica(0) { it.com("tipo", JsonPrimitive("float")) })
        invalido("curva", raiz.metrica(8) { it.com("curva", JsonPrimitive("inexistente")) })
        invalido("ordem repetida", raiz.metrica(1) { it.com("ordem", JsonPrimitive(1)) })
        invalido("id repetido", raiz.metrica(1) { it.com("id", JsonPrimitive("rpm")) })
        invalido("condição", raiz.metrica(0) { m ->
            m.com("faixas", JsonArray(listOf(m.getValue("faixas").jsonArray[0].jsonObject.com("quando", JsonPrimitive("nunca")))))
        })
        invalido("não existe", raiz.em("condicoes") { c ->
            c.com("ligado", c.getValue("ligado").jsonObject.com("regras", JsonArray(listOf(
                JsonObject(mapOf("metrica" to JsonPrimitive("fantasma"), "op" to JsonPrimitive(">"), "valor" to JsonPrimitive(1)))))))
        })
        invalido("circular", raiz.em("condicoes") { c ->
            c.com("sempre", JsonObject(mapOf("texto" to JsonPrimitive("x"),
                "regras" to JsonArray(listOf(JsonObject(mapOf("condicao" to JsonPrimitive("sempre"))))))))
        })
        invalido("nível", raiz.em("falhas") { f ->
            f.com("tipos", JsonObject(mapOf("1" to JsonObject(mapOf("nome" to JsonPrimitive("x"), "nivel" to JsonPrimitive("V9"))))))
        })
        invalido("captura", raiz.com("escrita", JsonArray(listOf(
            raiz.getValue("escrita").jsonArray[0].jsonObject.com("nivel", JsonPrimitive("pendente"))))))
    }

    @Test fun perfilSemCampoObrigatorioDaErro() {
        for (campo in listOf("conexao", "leitura", "metricas", "condicoes", "falhas", "id"))
            assertThrows(campo, PerfilInvalido::class.java) { Perfil.de(raiz.sem(campo).toString()) }
        assertThrows(PerfilInvalido::class.java) { Perfil.de("isto não é json") }
    }

    // --- escrita: somente leitura por padrão ------------------------------------------------------

    @Test fun escritaFicaBloqueadaPorPadrao() {
        val e = Psa5(Scanner(SimTransporte(perfil)), perfil)
        e.conectar()
        assertThrows(EscritaBloqueada::class.java) { e.escrever("apagar_falhas") }
        assertEquals(3, e.lerFalhas()!!.size)               // nada foi mandado à central
    }

    @Test fun escritaHabilitadaApagaEPedeUmIdQueExiste() {
        var liberada = true
        val e = Psa5(Scanner(SimTransporte(perfil)), perfil) { liberada }
        e.conectar()
        assertThrows(EscritaBloqueada::class.java) { e.escrever("nao_existe") }
        assertEquals("c3", e.escrever("apagar_falhas").hex())
        assertEquals(emptyList<Sagem.Falha>(), e.lerFalhas())
        liberada = false                                      // desligar a chave vale na hora
        assertThrows(EscritaBloqueada::class.java) { e.escrever("apagar_falhas") }
    }

    @Test fun conexaoRecusaApagarComAChaveDesligada() {
        val escopo = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val c = Conexao(perfil, escopo) { SimTransporte(perfil) }
        assertFalse(c.escrita)
        runBlocking { assertThrows(EscritaBloqueada::class.java) { runBlocking { c.apagarFalhas() } } }
    }
}
