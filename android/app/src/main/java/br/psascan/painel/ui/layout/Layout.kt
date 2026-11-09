@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package br.psascan.painel.ui.layout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/*
 * Layout da tela personalizável (docs/LAYOUT.md): quais widgets aparecem e onde. É só dados (JSON).
 * Este arquivo não depende do Android: o modelo, o catálogo dos tipos (assets/widgets/catalogo.json, o mesmo
 * da versão Linux) e a validação, com as mesmas regras e mensagens de psascan/layouts.py (os vetores de teste
 * vêm de lá). Métrica que não existe no perfil NÃO invalida o layout: a tela mostra o widget como indisponível.
 */

/** Layout recusado; [motivo] é a frase, em português, começando pelo caminho do campo (ex. "widgets[2].largura"). */
class LayoutInvalido(val motivo: String) : Exception(motivo)

/** O layout pedido não existe. */
class LayoutAusente(val id: String) : Exception("o layout \"$id\" não existe")

// ---------------------------------------------------------------------------------------------
// Modelo

@Serializable
data class GradeLayout(val colunas: Int, val linhas: Int)

@Serializable
data class WidgetLayout(
    val id: String,
    val tipo: String,
    val metrica: String? = null,
    val x: Int,
    val y: Int,
    val largura: Int,
    val altura: Int,
    val titulo: String? = null,
    val opcoes: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class Layout(
    val formato: Int,
    val id: String,
    val nome: String,
    val descricao: String? = null,
    val grade: GradeLayout,
    val orientacao: String? = null,
    val widgets: List<WidgetLayout>,
)

/** O layout tem widget que precisa da leitura de falhas (`falhas`, ou `luz` com `fonte: falhas`)? */
val Layout.usaFalhas: Boolean
    get() = widgets.any { it.tipo == "falhas" || it.tipo == "luz" && (it.opcoes["fonte"] as? JsonPrimitive)?.content == "falhas" }

// ---------------------------------------------------------------------------------------------
// Catálogo dos tipos de widget

@Serializable
data class EspOpcao(
    val tipo: String,
    val min: Double? = null,
    val max: Double? = null,
    @SerialName("min_exclusivo") val minExclusivo: Double? = null,
    val padrao: JsonElement? = null,
    val valores: List<String> = emptyList(),
    val campos: Map<String, EspOpcao> = emptyMap(),
    val obrigatorio: Boolean = false,
) {
    /** Objeto que só existe inteiro ou nenhum (tem campo obrigatório, como `arco_inferior`). */
    val objetoTudoOuNada get() = tipo == "objeto" && campos.values.any { it.obrigatorio }
}

@Serializable
data class TipoCatalogo(
    val nome: String,
    val minimo: List<Int>,
    val maximo: List<Int>,
    /** Categorias de métrica aceitas: `numerica`, `discreta`, `bit`. Vazio = o tipo não usa métrica. */
    val metricas: List<String>,
    /** O tipo dispensa a métrica quando estas opções têm estes valores (ex. `luz` com `fonte: falhas`). */
    @SerialName("sem_metrica_se") val semMetricaSe: Map<String, JsonElement> = emptyMap(),
    val opcoes: Map<String, EspOpcao> = emptyMap(),
) {
    /** O widget com estas opções dispensa a métrica? */
    fun dispensaMetrica(opcoes: JsonObject) = semMetricaSe.isNotEmpty() && semMetricaSe.all { (k, v) -> opcoes[k] == v }
    val usaMetrica get() = metricas.isNotEmpty()
}

@Serializable
data class Catalogo(val formato: Int = 1, val tipos: Map<String, TipoCatalogo>) {

    /** Categoria do catálogo para um `tipo` de métrica do perfil (u8, u16, curva, bit, enum). */
    fun categoria(tipoPerfil: String): String? = CATEGORIAS[tipoPerfil]

    /** O tipo de widget aceita a métrica deste tipo do perfil? `bit` conta como discreta também. */
    fun aceita(tipo: String, tipoPerfil: String): Boolean {
        val cat = categoria(tipoPerfil)
        val aceitas = tipos[tipo]?.metricas ?: return false
        return cat in aceitas || (cat == "bit" && "discreta" in aceitas)
    }

    /** As opções do layout completadas com os padrões do catálogo (objeto com obrigatórios só existe se o layout o pediu). */
    fun completarOpcoes(tipo: String, opcoes: JsonObject): JsonObject =
        completar(tipos[tipo]?.opcoes ?: emptyMap(), opcoes)

    /** Métricas citadas dentro das opções (`centro.metrica`, `arco_inferior.metrica`…): (caminho, id). */
    fun metricasDasOpcoes(tipo: String, opcoes: JsonObject): List<Pair<String, String>> =
        citadas(tipos[tipo]?.opcoes ?: emptyMap(), opcoes, "")

    private fun completar(campos: Map<String, EspOpcao>, dado: JsonObject?): JsonObject = buildJsonObject {
        for ((k, esp) in campos) {
            val v = dado?.get(k)
            when {
                v != null -> put(k, if (esp.tipo == "objeto") completar(esp.campos, v as? JsonObject) else v)
                esp.tipo == "objeto" -> {
                    if (!esp.objetoTudoOuNada) completar(esp.campos, null).takeIf { it.isNotEmpty() }?.let { put(k, it) }
                }
                esp.padrao != null -> put(k, esp.padrao)
            }
        }
    }

    private fun citadas(campos: Map<String, EspOpcao>, dado: JsonObject, caminho: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for ((k, esp) in campos) {
            val v = dado[k] ?: continue
            if (esp.tipo == "metrica") (v as? JsonPrimitive)?.takeIf { it.isString }?.let { out += (caminho + k) to it.content }
            else if (esp.tipo == "objeto" && v is JsonObject) {
                if (k == "centro" && (v["conteudo"] as? JsonPrimitive)?.content != "metrica") continue   // só vale com "outra métrica"
                out += citadas(esp.campos, v, "$caminho$k.")
            }
        }
        return out
    }

    companion object {
        val CATEGORIAS = mapOf("u8" to "numerica", "u16" to "numerica", "curva" to "numerica", "bit" to "bit", "enum" to "discreta")
        private val json = Json { ignoreUnknownKeys = true }

        fun de(texto: String): Catalogo = json.decodeFromString(serializer(), texto)
    }
}

// ---------------------------------------------------------------------------------------------
// Validação (a mesma de psascan/layouts.py)

object LimitesLayout {
    const val MAX_JSON = 256 * 1024
    const val MAX_WIDGETS = 40
    val COLUNAS = 4..16
    val LINHAS = 3..12
    val RE_ID = Regex("[a-z0-9-]{2,32}")
    val RE_ID_WIDGET = Regex("[a-z0-9_-]{1,32}")
    val RE_METRICA = Regex("[a-z0-9_]{1,40}")
    val ORIENTACOES = listOf("paisagem", "retrato", "qualquer")
    const val PADRAO = "painel"
}

private val jsonLayout = Json { prettyPrint = true; prettyPrintIndent = " "; encodeDefaults = false; explicitNulls = false }

/** O JSON do layout como o app grava e exporta (recuo de 1 espaço, igual ao da versão Linux). */
fun Layout.paraJson(): String = jsonLayout.encodeToString(Layout.serializer(), this) + "\n"

class ValidadorLayout(private val catalogo: Catalogo) {

    /** Lê e valida os bytes de um `.json`. */
    fun carregar(bytes: ByteArray): Layout {
        if (bytes.size > LimitesLayout.MAX_JSON) throw LayoutInvalido("arquivo grande demais (máximo ${LimitesLayout.MAX_JSON / 1024} KB)")
        val texto = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw LayoutInvalido("JSON inválido: o arquivo não está em UTF-8")
        }
        return carregar(texto)
    }

    fun carregar(texto: String): Layout {
        // o analisador do kotlinx aceita texto solto no topo ("lixo"); JSON de verdade começa por um destes
        if (texto.trimStart().firstOrNull()?.let { it in "{[\"-0123456789tfn" } != true) throw LayoutInvalido("JSON inválido: não é JSON")
        val raiz = try {
            Json.parseToJsonElement(texto)
        } catch (e: SerializationException) {
            throw LayoutInvalido("JSON inválido: ${e.message?.lineSequence()?.firstOrNull()}")
        } catch (e: IllegalArgumentException) {
            throw LayoutInvalido("JSON inválido: ${e.message?.lineSequence()?.firstOrNull()}")
        } catch (_: StackOverflowError) {
            throw LayoutInvalido("JSON inválido: aninhado demais")
        }
        return validar(raiz)
    }

    /** Valida um layout montado em código (o editor): passa pelas mesmas regras do arquivo. */
    fun validar(layout: Layout): Layout = validar(jsonLayout.encodeToJsonElement(Layout.serializer(), layout))

    /** Estrutura e valores; devolve o layout ou lança [LayoutInvalido]. */
    fun validar(raiz: JsonElement): Layout {
        val tipos = catalogo.tipos
        val l = objeto(raiz, "layout")
        conhecidos(l, listOf("formato", "id", "nome", "descricao", "grade", "orientacao", "widgets"), "")
        if (!l["formato"].ehInt() || l["formato"].inteiro() != 1L) throw LayoutInvalido("formato: deveria ser 1")
        val id = l["id"]
        if (!id.ehTexto() || !LimitesLayout.RE_ID.matches(id.texto())) throw LayoutInvalido("id: use a-z, 0-9 e hífen, de 2 a 32 caracteres")
        if ("nome" !in l) throw LayoutInvalido("nome: campo obrigatório")
        texto(l["nome"], "nome", 40, 1)
        if ("descricao" in l) texto(l["descricao"], "descricao", 300)
        if ("orientacao" in l && (!l["orientacao"].ehTexto() || l["orientacao"].texto() !in LimitesLayout.ORIENTACOES))
            throw LayoutInvalido("orientacao: valor fora da lista (${LimitesLayout.ORIENTACOES.joinToString(", ")})")
        if ("grade" !in l) throw LayoutInvalido("grade: campo obrigatório")
        val g = objeto(l["grade"], "grade")
        conhecidos(g, listOf("colunas", "linhas"), "grade")
        for ((k, faixa) in listOf("colunas" to LimitesLayout.COLUNAS, "linhas" to LimitesLayout.LINHAS)) {
            if (k !in g) throw LayoutInvalido("grade.$k: campo obrigatório")
            inteiro(g[k], "grade.$k", faixa.first.toLong(), faixa.last.toLong())
        }
        val colunas = g["colunas"].inteiro().toInt()
        val linhas = g["linhas"].inteiro().toInt()
        if ("widgets" !in l) throw LayoutInvalido("widgets: campo obrigatório")
        val lista = (l["widgets"] as? kotlinx.serialization.json.JsonArray) ?: throw LayoutInvalido("widgets: deveria ser uma lista")
        if (lista.size > LimitesLayout.MAX_WIDGETS) throw LayoutInvalido("widgets: no máximo ${LimitesLayout.MAX_WIDGETS} widgets")
        val ids = HashSet<String>()
        val ocupado = HashMap<Pair<Int, Int>, String>()
        lista.forEachIndexed { i, we ->
            val c = "widgets[$i]"
            val w = objeto(we, c)
            conhecidos(w, listOf("id", "tipo", "metrica", "x", "y", "largura", "altura", "titulo", "opcoes"), c)
            val wid = w["id"]
            if (!wid.ehTexto() || !LimitesLayout.RE_ID_WIDGET.matches(wid.texto()))
                throw LayoutInvalido("$c.id: use a-z, 0-9, _ e hífen, de 1 a 32 caracteres")
            if (!ids.add(wid.texto())) throw LayoutInvalido("$c.id: \"${wid.texto()}\" repetido (cada widget precisa de um id único)")
            val nomeTipo = w["tipo"]
            if (!nomeTipo.ehTexto() || nomeTipo.texto() !in tipos)
                throw LayoutInvalido("$c.tipo: tipo desconhecido (${tipos.keys.joinToString(", ")})")
            val tipo = tipos.getValue(nomeTipo.texto())
            val op = if ("opcoes" in w) objeto(w["opcoes"], "$c.opcoes") else JsonObject(emptyMap())
            if ("metrica" in w) {
                if (!tipo.usaMetrica && tipo.semMetricaSe.isEmpty()) throw LayoutInvalido("$c.metrica: o widget \"${nomeTipo.texto()}\" não usa métrica")
                val m = w["metrica"]
                if (!m.ehTexto() || !LimitesLayout.RE_METRICA.matches(m.texto()))
                    throw LayoutInvalido("$c.metrica: id de métrica inválido (use a-z, 0-9 e _)")
            } else if (tipo.usaMetrica && !tipo.dispensaMetrica(op)) {
                throw LayoutInvalido("$c.metrica: campo obrigatório para o widget \"${nomeTipo.texto()}\"")
            }
            for (k in listOf("x", "y", "largura", "altura")) {
                if (k !in w) throw LayoutInvalido("$c.$k: campo obrigatório")
                inteiro(w[k], "$c.$k", 0, 1_000_000)
            }
            for ((k, eixo) in listOf("largura" to 0, "altura" to 1)) {
                val lo = tipo.minimo[eixo]; val hi = tipo.maximo[eixo]
                val v = w[k].inteiro()
                if (v < lo || v > hi) throw LayoutInvalido("$c.$k: o widget \"${nomeTipo.texto()}\" aceita de $lo a $hi células")
            }
            val x = w["x"].inteiro().toInt(); val y = w["y"].inteiro().toInt()
            val larg = w["largura"].inteiro().toInt(); val alt = w["altura"].inteiro().toInt()
            if (larg < 1 || alt < 1) throw LayoutInvalido("$c: tamanho vazio")
            if (x + larg > colunas) throw LayoutInvalido("$c.x: o widget sai da grade (colunas 0 a ${colunas - 1})")
            if (y + alt > linhas) throw LayoutInvalido("$c.y: o widget sai da grade (linhas 0 a ${linhas - 1})")
            if ("titulo" in w) texto(w["titulo"], "$c.titulo", 40)
            conhecidos(op, tipo.opcoes.keys, "$c.opcoes")
            for ((k, v) in op) opcao(v, tipo.opcoes.getValue(k), "$c.opcoes.$k")
            for (yy in y until y + alt) for (xx in x until x + larg) {
                ocupado[xx to yy]?.let { throw LayoutInvalido("$c: sobrepõe o widget \"$it\"") }
                ocupado[xx to yy] = wid.texto()
            }
        }
        return try {
            Json.decodeFromJsonElement(Layout.serializer(), raiz)
        } catch (e: SerializationException) {   // não deve acontecer: a validação acima cobre o modelo
            throw LayoutInvalido("layout: ${e.message?.lineSequence()?.firstOrNull()}")
        }
    }

    // ------------------------------------------------------------------ pedaços

    private fun JsonElement?.ehInt() = this is JsonPrimitive && !isString && content.toLongOrNull() != null
    private fun JsonElement?.ehNum() = this is JsonPrimitive && !isString && content.toDoubleOrNull()?.isFinite() == true
    private fun JsonElement?.ehTexto() = this is JsonPrimitive && isString
    private fun JsonElement?.inteiro() = (this as JsonPrimitive).content.toLong()
    private fun JsonElement?.numero() = (this as JsonPrimitive).content.toDouble()
    private fun JsonElement?.texto() = (this as JsonPrimitive).content

    private fun objeto(v: JsonElement?, c: String): JsonObject = v as? JsonObject ?: throw LayoutInvalido("$c: deveria ser um objeto")

    private fun conhecidos(obj: JsonObject, validos: Collection<String>, c: String) {
        for (k in obj.keys) if (k !in validos) throw LayoutInvalido("${if (c.isNotEmpty()) "$c.$k" else k}: campo desconhecido")
    }

    private fun texto(v: JsonElement?, c: String, maximo: Int, minimo: Int = 0) {
        if (!v.ehTexto() || v.texto().let { it.codePointCount(0, it.length) } !in minimo..maximo)
            throw LayoutInvalido("$c: texto de $minimo a $maximo caracteres")
    }

    private fun inteiro(v: JsonElement?, c: String, lo: Long, hi: Long) {
        if (!v.ehInt()) throw LayoutInvalido("$c: deveria ser um número inteiro")
        if (v.inteiro() !in lo..hi) throw LayoutInvalido("$c: fora da faixa $lo a $hi")
    }

    private fun fmt(d: Double) = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun faixa(v: Double, esp: EspOpcao, c: String) {
        esp.min?.let { if (v < it) throw LayoutInvalido("$c: fora da faixa (mínimo ${fmt(it)})") }
        esp.max?.let { if (v > it) throw LayoutInvalido("$c: fora da faixa (máximo ${fmt(it)})") }
        esp.minExclusivo?.let { if (v <= it) throw LayoutInvalido("$c: deveria ser maior que ${fmt(it)}") }
    }

    /** Valida um valor de opção contra a descrição do catálogo (tipo, min/max, valores, campos). */
    private fun opcao(v: JsonElement, esp: EspOpcao, c: String) {
        when (esp.tipo) {
            "bool" -> if (v !is JsonPrimitive || v.isString || v.content != "true" && v.content != "false")
                throw LayoutInvalido("$c: deveria ser verdadeiro ou falso")
            "inteiro" -> {
                if (!v.ehInt()) throw LayoutInvalido("$c: deveria ser um número inteiro")
                faixa(v.inteiro().toDouble(), esp, c)
            }
            "numero" -> {
                if (!v.ehNum()) throw LayoutInvalido("$c: deveria ser um número")
                faixa(v.numero(), esp, c)
            }
            "lista" -> if (!v.ehTexto() || v.texto() !in esp.valores)
                throw LayoutInvalido("$c: valor fora da lista (${esp.valores.joinToString(", ")})")
            "texto" -> texto(v, c, esp.max?.toInt() ?: 200)
            "metrica" -> if (!v.ehTexto() || !LimitesLayout.RE_METRICA.matches(v.texto()))
                throw LayoutInvalido("$c: id de métrica inválido (use a-z, 0-9 e _)")
            "objeto" -> {
                val o = objeto(v, c)
                conhecidos(o, esp.campos.keys, c)
                for ((k, sub) in esp.campos) {
                    val x = o[k]
                    if (x != null) opcao(x, sub, "$c.$k") else if (sub.obrigatorio) throw LayoutInvalido("$c.$k: campo obrigatório")
                }
            }
            else -> throw LayoutInvalido("$c: tipo de opção desconhecido no catálogo (${esp.tipo})")
        }
    }
}
