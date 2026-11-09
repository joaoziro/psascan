@file:UseSerializers(CorSerializer::class)

package br.psascan.painel.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.pow

/*
 * Tema do app (docs/TEMA.md): só dados, sem código. O app usa as seções `painel` e `mascote`;
 * a `interface` é do painel web, mas é validada e guardada para o arquivo continuar valendo nos dois.
 * Este arquivo não depende do Android (a não ser pela fonte do sistema): dá para testar na JVM.
 * Os temas embutidos vêm de assets/temas/<id>/ (copiados de temas/ por gerar_dados.py), os importados
 * ficam em filesDir/temas/<id>/ e o ativo em SharedPreferences (ver TemaRepositorio.kt).
 * Os únicos literais de cor do app ficam no Padrão (temas/padrao/tema.json): nenhum no código.
 */

/** O tema não segue o contrato; [motivo] é a frase que vai para a tela. */
class TemaInvalido(val motivo: String) : Exception(motivo)

/** `#rrggbb` ou `#rrggbbaa`. */
object CorSerializer : KSerializer<Color> {
    override val descriptor = PrimitiveSerialDescriptor("Cor", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Color) {
        val v = value.value.toLong()   // não usado para gravar (o app só lê temas), mas mantém o serializador completo
        encoder.encodeString("#%08x".format(v))
    }
    override fun deserialize(decoder: Decoder): Color =
        corDeTexto(decoder.decodeString()) ?: throw SerializationException("cor inválida (use #rrggbb ou #rrggbbaa)")
}

private val FORMATO_COR = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")

fun corDeTexto(s: String): Color? {
    if (!FORMATO_COR.matches(s)) return null
    val r = s.substring(1, 3).toInt(16); val g = s.substring(3, 5).toInt(16); val b = s.substring(5, 7).toInt(16)
    val a = if (s.length == 9) s.substring(7, 9).toInt(16) else 255
    return Color(r, g, b, a)
}

// ---------------------------------------------------------------------------------------------
// Modelo (os nomes de chave são os do tema.json)

@Serializable
enum class EstiloPonteiro {
    @SerialName("agulha") AGULHA,
    @SerialName("seta") SETA,
    @SerialName("barra") BARRA,
}

@Serializable
enum class AnimacaoPonteiro {
    @SerialName("mola") MOLA,
    @SerialName("suave") SUAVE,
    @SerialName("nenhuma") NENHUMA,
}

@Serializable
enum class SistemaFonte {
    @SerialName("condensada") CONDENSADA,
    @SerialName("padrao") PADRAO,
    @SerialName("serifada") SERIFADA,
    @SerialName("mono") MONO,
}

@Serializable
enum class PosicaoMascote {
    @SerialName("canto") CANTO,
    @SerialName("centro") CENTRO,
}

@Serializable
data class Cores(
    val fundo: Color,
    val texto: Color,
    @SerialName("texto_medio") val textoMedio: Color,
    @SerialName("texto_fino") val textoFino: Color,
    @SerialName("texto_apagado") val textoApagado: Color,
    val passou: Color,
    val ambar: Color,
    val alerta: Color,
    val ok: Color,
    /** Degradê do aro: cima, meio, baixo. */
    val aro: List<Color>,
    @SerialName("aro_interno") val aroInterno: Color,
    /** Degradê radial do fundo do mostrador: centro, 70 %, borda. */
    val face: List<Color>,
    /** Degradê da agulha de um lado ao outro: luz, meio, sombra, borda. */
    val ponteiro: List<Color>,
    @SerialName("ponteiro_aresta") val ponteiroAresta: Color,
    @SerialName("ponteiro_brilho") val ponteiroBrilho: Color,
    val sombra: Color,
    @SerialName("contorno_numeros") val contornoNumeros: Color,
    /** Disco central: centro, borda. */
    val miolo: List<Color>,
    /** Borda chanfrada do miolo: cima, meio, baixo. */
    val chanfro: List<Color>,
    @SerialName("rolo_fundo") val roloFundo: Color,
    @SerialName("rolo_borda") val roloBorda: Color,
    @SerialName("rolo_texto") val roloTexto: Color,
    @SerialName("rolo_destaque_fundo") val roloDestaqueFundo: Color,
    @SerialName("rolo_destaque_borda") val roloDestaqueBorda: Color,
    @SerialName("rolo_destaque_texto") val roloDestaqueTexto: Color,
    @SerialName("agua_trilho") val aguaTrilho: Color,
    @SerialName("luz_apagada") val luzApagada: Color,
    @SerialName("rotulo_fundo") val rotuloFundo: Color,
    val superficie: Color,
    @SerialName("superficie_2") val superficie2: Color,
    val borda: Color,
    val campo: Color,
    @SerialName("grafico_grade") val graficoGrade: Color,
    @SerialName("grafico_limite") val graficoLimite: Color,
    @SerialName("chave_ligada_trilho") val chaveLigadaTrilho: Color,
)

/** As cores do painel com as de [o] por cima (`peles.mostrador.cores`, já validadas); vazio devolve as mesmas. */
fun Cores.sobrepor(o: JsonObject): Cores {
    if (o.isEmpty()) return this
    fun c(k: String, base: Color) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::corDeTexto) ?: base
    fun l(k: String, base: List<Color>): List<Color> {
        val a = o[k] as? JsonArray ?: return base
        val novas = a.map { ((it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content)?.let(::corDeTexto) }
        return if (novas.size == base.size && novas.all { it != null }) novas.filterNotNull() else base
    }
    return copy(
        fundo = c("fundo", fundo), texto = c("texto", texto), textoMedio = c("texto_medio", textoMedio),
        textoFino = c("texto_fino", textoFino), textoApagado = c("texto_apagado", textoApagado),
        passou = c("passou", passou), ambar = c("ambar", ambar), alerta = c("alerta", alerta), ok = c("ok", ok),
        aro = l("aro", aro), aroInterno = c("aro_interno", aroInterno), face = l("face", face),
        ponteiro = l("ponteiro", ponteiro), ponteiroAresta = c("ponteiro_aresta", ponteiroAresta),
        ponteiroBrilho = c("ponteiro_brilho", ponteiroBrilho), sombra = c("sombra", sombra),
        contornoNumeros = c("contorno_numeros", contornoNumeros), miolo = l("miolo", miolo), chanfro = l("chanfro", chanfro),
        roloFundo = c("rolo_fundo", roloFundo), roloBorda = c("rolo_borda", roloBorda), roloTexto = c("rolo_texto", roloTexto),
        roloDestaqueFundo = c("rolo_destaque_fundo", roloDestaqueFundo), roloDestaqueBorda = c("rolo_destaque_borda", roloDestaqueBorda),
        roloDestaqueTexto = c("rolo_destaque_texto", roloDestaqueTexto), aguaTrilho = c("agua_trilho", aguaTrilho),
        luzApagada = c("luz_apagada", luzApagada), rotuloFundo = c("rotulo_fundo", rotuloFundo),
        superficie = c("superficie", superficie), superficie2 = c("superficie_2", superficie2), borda = c("borda", borda),
        campo = c("campo", campo), graficoGrade = c("grafico_grade", graficoGrade), graficoLimite = c("grafico_limite", graficoLimite),
        chaveLigadaTrilho = c("chave_ligada_trilho", chaveLigadaTrilho),
    )
}

@Serializable
data class FonteWeb(val familia: String, val arquivos: Map<String, String> = emptyMap())

@Serializable
data class FonteApp(val sistema: SistemaFonte, val arquivo: String? = null)

@Serializable
data class Fonte(val web: FonteWeb, val app: FonteApp)

@Serializable
data class Espessuras(
    val aro: Float,
    @SerialName("traco_grande") val tracoGrande: Float,
    @SerialName("traco_medio") val tracoMedio: Float,
    @SerialName("traco_fino") val tracoFino: Float,
    @SerialName("ponteiro_base") val ponteiroBase: Float,
    @SerialName("ponteiro_ponta") val ponteiroPonta: Float,
    @SerialName("contorno_numeros") val contornoNumeros: Float,
    val agua: Float,
    val alerta: Float,
)

@Serializable
data class EstiloDoPonteiro(
    val estilo: EstiloPonteiro,
    val brilho: Float,
    val sombra: Float,
    @SerialName("raio_inicio") val raioInicio: Float,
    @SerialName("raio_ponta") val raioPonta: Float,
)

@Serializable
data class EscalaTema(
    val niveis: Int,
    @SerialName("pintar_passou") val pintarPassou: Boolean,
    @SerialName("numeros_por_cima") val numerosPorCima: Boolean,
)

@Serializable
data class RaiosTema(val miolo: Float, val rotulo: Float, val cartao: Float)

@Serializable
data class AnimacaoTema(val ponteiro: AnimacaoPonteiro, val rapidez: Float)

@Serializable
data class Painel(
    val cores: Cores,
    val fonte: Fonte,
    val espessuras: Espessuras,
    val ponteiro: EstiloDoPonteiro,
    val escala: EscalaTema,
    val raios: RaiosTema,
    val animacao: AnimacaoTema,
    /** O desenho de cada tipo de widget (docs/TEMA.md, painel.peles); tudo opcional. */
    val peles: Peles = Peles(),
)

// Peles (docs/TEMA.md): imagens por peça, geometria e fontes de cada tipo de widget. Os caminhos são
// "arquivos/<nome>" na pasta do tema (ver ArquivosTema.kt). Campo ausente = o motor desenha com as cores do tema.

@Serializable
data class AgulhaPele(val arquivo: String, val pivo: List<Float> = listOf(.5f, .5f), val comprimento: Float = .45f)

@Serializable
data class ImagensMostrador(
    val fundo: String? = null, val agulha: AgulhaPele? = null, val miolo: String? = null, val vidro: String? = null,
    @SerialName("mascara_arco") val mascaraArco: String? = null,
)

/** Liga/desliga cada parte desenhada pelo motor; null = o padrão (ligada, ou desligada quando há imagem da mesma parte). */
@Serializable
data class DesenharMostrador(
    val aro: Boolean? = null, val escala: Boolean? = null, val numeros: Boolean? = null,
    val ponteiro: Boolean? = null, val miolo: Boolean? = null,
)

@Serializable
data class ArcoPele(
    val mostrar: Boolean = true, val raio: Float = 84f, val espessura: Float = 9f, val estilo: String = "continuo",
    val segmentos: Int = 48, val cor: Color? = null, @SerialName("cor_alerta") val corAlerta: Color? = null,
)

@Serializable
data class DigitalPele(
    val posicao: List<Float>? = null, val tamanho: Float? = null, val fonte: String? = null,
    @SerialName("fonte_app") val fonteApp: String? = null, val cor: Color? = null,
    val brilho: Float = 0f, val apagados: Boolean = false,
)

@Serializable
data class PeleMostrador(
    @SerialName("angulo_inicio") val anguloInicio: Float = -128f,
    @SerialName("angulo_fim") val anguloFim: Float = 128f,
    val imagens: ImagensMostrador = ImagensMostrador(), val desenhar: DesenharMostrador = DesenharMostrador(),
    val arco: ArcoPele? = null, val digital: DigitalPele? = null,
    /** Cores só deste mostrador, com as chaves de `painel.cores` (validadas na carga); aplique com [Cores.sobrepor]. */
    val cores: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ImagensBarra(val trilho: String? = null, val cheia: String? = null)

@Serializable
data class PeleBarra(
    val estilo: String = "continua", val segmentos: Int = 20, val espaco: Float = .2f, val raio: Float? = null,
    val imagens: ImagensBarra = ImagensBarra(),
)

@Serializable
data class PeleNumero(
    val fonte: String? = null, @SerialName("fonte_app") val fonteApp: String? = null,
    val brilho: Float = 0f, val apagados: Boolean = false,
)

@Serializable
data class PeleNumeroHistorico(val linha: Float = 1.6f, val preencher: Boolean = false)

@Serializable
data class ImagensLuz(val apagada: String? = null, val acesa: String? = null)

@Serializable
data class PeleLuz(val imagens: ImagensLuz = ImagensLuz(), val brilho: Float = .8f)

@Serializable
data class PeleEstado(val fonte: String? = null, @SerialName("fonte_app") val fonteApp: String? = null, val caixa: Boolean = false)

@Serializable
data class PeleFalhas(val fonte: String? = null, @SerialName("fonte_app") val fonteApp: String? = null)

@Serializable
data class Peles(
    val mostrador: PeleMostrador = PeleMostrador(), val barra: PeleBarra = PeleBarra(),
    val numero: PeleNumero = PeleNumero(),
    @SerialName("numero_historico") val numeroHistorico: PeleNumeroHistorico = PeleNumeroHistorico(),
    val luz: PeleLuz = PeleLuz(), val estado: PeleEstado = PeleEstado(), val falhas: PeleFalhas = PeleFalhas(),
)

@Serializable
data class Mascote(val imagem: String, val posicao: PosicaoMascote, val nome: String? = null)

@Serializable
data class Tema(
    val formato: Int,
    val id: String,
    val nome: String,
    val descricao: String? = null,
    val autor: String? = null,
    /** Painel web: o app não usa, só guarda (e valida). */
    @SerialName("interface") val interfaceWeb: JsonElement? = null,
    val painel: Painel,
    val mascote: Mascote? = null,
)

// ---------------------------------------------------------------------------------------------
// Tema ativo, lido por toda a UI

/** Um tema pronto para uso: os dados e a família de fonte já resolvida (do sistema ou do .ttf do tema). */
class TemaCarregado(val tema: Tema, val familia: FontFamily)

/** Fonte monoespaçada para bytes e log: sempre a do sistema, qualquer que seja o tema. */
val FONTE_MONO: FontFamily = FontFamily.Monospace

/** As fontes do Android que o contrato permite em `app.sistema`. */
fun familiaDoSistema(s: SistemaFonte): FontFamily = when (s) {
    SistemaFonte.CONDENSADA -> FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))
    SistemaFonte.PADRAO -> FontFamily.Default
    SistemaFonte.SERIFADA -> FontFamily.Serif
    SistemaFonte.MONO -> FontFamily.Monospace
}

/**
 * Estado global observável do tema ativo. Trocar [ativo] redesenha tudo o que leu [painel]/[familia]
 * (o Compose registra a leitura, também dentro do Canvas).
 */
object Temas {
    var ativo by mutableStateOf<TemaCarregado?>(null)
    private val ou get() = ativo ?: error("tema não carregado (App.onCreate chama Temas.ativo = …)")
    val tema: Tema get() = ou.tema
    val painel: Painel get() = ou.tema.painel
    val familia: FontFamily get() = ou.familia
}

// ---------------------------------------------------------------------------------------------
// Validação

/** Os arquivos que acompanham o tema.json: caminho ("arquivos/x.ttf") → bytes. */
class Pacote(val json: String, val arquivos: Map<String, ByteArray> = emptyMap())

object LimitesTema {
    const val POR_ARQUIVO = 2 * 1024 * 1024
    const val TOTAL = 5 * 1024 * 1024
    const val JSON = 256 * 1024
    const val ENTRADAS = 64
    val EXTENSOES = setOf("woff2", "ttf", "png", "webp", "jpg")
    val IMAGENS = setOf("png", "webp", "jpg")
}

private const val F = ""   // folha do esquema das peles
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val ESQUEMA_PELES: Map<String, Any> = mapOf(
    "mostrador" to mapOf(
        "angulo_inicio" to F, "angulo_fim" to F,
        "imagens" to mapOf("fundo" to F, "agulha" to mapOf("arquivo" to F, "pivo" to F, "comprimento" to F),
            "miolo" to F, "vidro" to F, "mascara_arco" to F),
        "desenhar" to mapOf("aro" to F, "escala" to F, "numeros" to F, "ponteiro" to F, "miolo" to F),
        "arco" to mapOf("mostrar" to F, "raio" to F, "espessura" to F, "estilo" to F, "segmentos" to F, "cor" to F, "cor_alerta" to F),
        "digital" to mapOf("posicao" to F, "tamanho" to F, "fonte" to F, "fonte_app" to F, "cor" to F, "brilho" to F, "apagados" to F),
        "cores" to Cores.serializer().descriptor.elementNames.associateWith { F },   // as mesmas chaves de painel.cores
    ),
    "barra" to mapOf("estilo" to F, "segmentos" to F, "espaco" to F, "raio" to F, "imagens" to mapOf("trilho" to F, "cheia" to F)),
    "numero" to mapOf("fonte" to F, "fonte_app" to F, "brilho" to F, "apagados" to F),
    "numero_historico" to mapOf("linha" to F, "preencher" to F),
    "luz" to mapOf("imagens" to mapOf("apagada" to F, "acesa" to F), "brilho" to F),
    "estado" to mapOf("fonte" to F, "fonte_app" to F, "caixa" to F),
    "falhas" to mapOf("fonte" to F, "fonte_app" to F),
)

/** As cores de `painel.cores` que são listas, e quantas cores cada uma tem. */
private val TAMANHO_LISTAS_CORES = mapOf("aro" to 3, "face" to 3, "ponteiro" to 4, "miolo" to 2, "chanfro" to 3)

private val FORMATO_ID = Regex("[a-z0-9-]{2,32}")
private val FORMATO_FONTE = Regex("[A-Za-z0-9 ,\"'-]+")
private val FORMATO_PESO = Regex("[1-9]00")

/** `arquivos/<nome>.<ext>` com extensão permitida, sem `..`, `/` no início, `\` ou subpasta. */
fun caminhoDeArquivoValido(p: String): Boolean {
    if (!p.startsWith("arquivos/")) return false
    val nome = p.removePrefix("arquivos/")
    if (nome.isEmpty() || '/' in nome || '\\' in nome || nome.startsWith(".") || ".." in nome || '\u0000' in nome) return false
    return nome.substringAfterLast('.', "").lowercase() in LimitesTema.EXTENSOES
}

private fun luminancia(c: Color): Double {
    fun lin(x: Float) = x.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}

/** Contraste WCAG entre duas cores (a transparência é ignorada). */
fun contraste(a: Color, b: Color): Double {
    val la = luminancia(a); val lb = luminancia(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * Valida e completa temas. [padraoJson] é o tema Padrão (assets/temas/padrao/tema.json): campo ausente
 * vem dele; ele próprio é validado ao construir.
 */
class ValidadorTema(padraoJson: String) {
    private val json = Json   // padrão: ignoreUnknownKeys = false, tipos estritos
    private val padrao: JsonObject = (json.parseToJsonElement(padraoJson) as JsonObject)
    val tema: Tema = validar(padraoJson)

    /** Lança [TemaInvalido] com o motivo. [arquivos] = caminhos presentes na pasta do tema. */
    fun validar(texto: String, arquivos: Set<String> = emptySet()): Tema {
        if (texto.length > LimitesTema.JSON) throw TemaInvalido("tema.json grande demais (máximo ${LimitesTema.JSON / 1024} kB)")
        val usuario = try {
            json.parseToJsonElement(texto)
        } catch (e: Exception) {
            throw TemaInvalido("JSON inválido: ${e.message?.lineSequence()?.firstOrNull() ?: e::class.simpleName}")
        } catch (_: StackOverflowError) {
            throw TemaInvalido("JSON inválido: aninhado demais")
        } as? JsonObject ?: throw TemaInvalido("JSON inválido: o tema deve ser um objeto")
        conferirChaves(usuario, padrao, "")
        for (k in listOf("id", "nome")) if (k !in usuario) throw TemaInvalido("falta o campo $k")
        val completo = mesclar(padrao, usuario)
        conferirEnumeracoes(completo)
        val t = try {
            json.decodeFromJsonElement(Tema.serializer(), completo)
        } catch (e: SerializationException) {
            throw TemaInvalido("valor inválido: ${e.message?.lineSequence()?.firstOrNull()}")
        } catch (e: IllegalArgumentException) {
            throw TemaInvalido("valor inválido: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        conferirCoresDoTexto(completo)
        conferirValores(t, arquivos)
        t.interfaceWeb?.let { conferirInterface(it) }
        return t
    }

    /** Campo que não existe no Padrão é chave desconhecida (a `interface` e o `mascote` têm regras próprias). */
    private fun conferirChaves(u: JsonObject, ref: JsonObject, caminho: String) {
        for ((k, v) in u) {
            val c = if (caminho.isEmpty()) k else "$caminho.$k"
            if (caminho == "painel.fonte.web.arquivos") {
                if (!FORMATO_PESO.matches(k)) throw TemaInvalido("peso de fonte inválido em $c (use 100 a 900)")
                continue
            }
            if (caminho == "mascote") {
                if (k !in setOf("imagem", "posicao", "nome")) throw TemaInvalido("chave desconhecida: $c")
                continue
            }
            if (caminho == "painel" && k == "peles") {   // as peles têm esquema próprio (o Padrão não precisa trazê-las)
                if (v !is JsonObject) throw TemaInvalido("$c deve ser um objeto")
                conferirChavesPeles(v, ESQUEMA_PELES, c)
                continue
            }
            if (k !in ref) throw TemaInvalido("chave desconhecida: $c")
            val r = ref[k]
            if (v is JsonObject && r is JsonObject) conferirChaves(v, r, c)
            else if (k == "mascote" && v is JsonObject) conferirChaves(v, JsonObject(emptyMap()), c)
            else if (r is JsonObject && v !is JsonObject) throw TemaInvalido("$c deve ser um objeto")
        }
    }

    /** Chaves das peles: o esquema é um mapa de mapas; folha = qualquer valor. */
    private fun conferirChavesPeles(u: JsonObject, esquema: Map<String, Any>, caminho: String) {
        for ((k, v) in u) {
            val c = "$caminho.$k"
            val sub = esquema[k] ?: throw TemaInvalido("chave desconhecida: $c")
            if (sub is Map<*, *>) {
                if (v is JsonNull) continue
                if (v !is JsonObject) throw TemaInvalido("$c deve ser um objeto")
                @Suppress("UNCHECKED_CAST")
                conferirChavesPeles(v, sub as Map<String, Any>, c)
            } else if (v is JsonObject || v is JsonArray && k != "pivo" && k != "posicao" && !caminho.endsWith(".cores")) throw TemaInvalido("valor inválido em $c")
        }
    }

    /** Enumerações fechadas do contrato, conferidas antes de decodificar para o motivo trazer o caminho do campo. */
    private fun conferirEnumeracoes(t: JsonObject) {
        fun texto(vararg cam: String): String? {
            var e: JsonElement? = t
            for (c in cam) e = (e as? JsonObject)?.get(c) ?: return null
            return (e as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
        for ((cam, ok) in listOf(
            listOf("painel", "ponteiro", "estilo") to setOf("agulha", "seta", "barra"),
            listOf("painel", "animacao", "ponteiro") to setOf("mola", "suave", "nenhuma"),
            listOf("painel", "fonte", "app", "sistema") to setOf("condensada", "padrao", "serifada", "mono"),
            listOf("mascote", "posicao") to setOf("canto", "centro"),
        )) {
            val v = texto(*cam.toTypedArray()) ?: continue
            if (v !in ok) throw TemaInvalido("${cam.joinToString(".")}: valor fora da lista ($v); use ${ok.joinToString(", ")}")
        }
    }

    /** O tema do usuário completado com o Padrão (sem validar), para conferir com o Python. */
    fun completar(texto: String): JsonObject = mesclar(padrao, json.parseToJsonElement(texto) as JsonObject)

    private fun mesclar(base: JsonObject, u: JsonObject): JsonObject {
        val saida = base.toMutableMap()
        for ((k, v) in u) {
            val b = base[k]
            saida[k] = if (b is JsonObject && v is JsonObject) mesclar(b, v) else v
        }
        return JsonObject(saida)
    }

    private fun cor(caminho: String, v: JsonElement?) {
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (s == null || corDeTexto(s) == null) throw TemaInvalido("cor inválida em $caminho (use #rrggbb ou #rrggbbaa)")
    }

    /** Antes de converter, diz qual campo tem a cor ruim (o serializador não informa o caminho). */
    private fun conferirCoresDoTexto(completo: JsonObject) {
        val cores = ((completo["painel"] as? JsonObject)?.get("cores") as? JsonObject) ?: return
        for ((k, v) in cores) {
            if (v is JsonArray) v.forEachIndexed { i, x -> cor("painel.cores.$k[$i]", x) } else cor("painel.cores.$k", v)
        }
    }

    private fun faixa(nome: String, v: Float, lo: Float, hi: Float) {
        if (!(v >= lo && v <= hi)) throw TemaInvalido("$nome fora da faixa ($lo a $hi): $v")
    }

    private fun lista(nome: String, l: List<Color>, n: Int) {
        if (l.size != n) throw TemaInvalido("$nome deve ter $n cores (tem ${l.size})")
    }

    private fun arquivo(nome: String, p: String, exts: Set<String>, arquivos: Set<String>) {
        if (!caminhoDeArquivoValido(p)) throw TemaInvalido("caminho de arquivo inválido em $nome: $p")
        if (p.substringAfterLast('.').lowercase() !in exts) throw TemaInvalido("$nome deve ser ${exts.joinToString("/")}: $p")
        if (p !in arquivos) throw TemaInvalido("arquivo citado em $nome não existe no tema: $p")
    }

    private fun conferirValores(t: Tema, arquivos: Set<String>) {
        if (t.formato != 1) throw TemaInvalido("formato ${t.formato} não suportado (este app lê o formato 1)")
        if (!FORMATO_ID.matches(t.id)) throw TemaInvalido("id inválido: use a-z, 0-9 e hífen, de 2 a 32 caracteres")
        if (t.nome.isBlank() || t.nome.length > 40) throw TemaInvalido("nome inválido: de 1 a 40 caracteres")
        if ((t.descricao?.length ?: 0) > 500) throw TemaInvalido("descricao grande demais (máximo 500 caracteres)")
        if ((t.autor?.length ?: 0) > 100) throw TemaInvalido("autor grande demais (máximo 100 caracteres)")
        val p = t.painel
        lista("painel.cores.aro", p.cores.aro, 3); lista("painel.cores.face", p.cores.face, 3)
        lista("painel.cores.ponteiro", p.cores.ponteiro, 4); lista("painel.cores.miolo", p.cores.miolo, 2)
        lista("painel.cores.chanfro", p.cores.chanfro, 3)
        val e = p.espessuras
        faixa("painel.espessuras.aro", e.aro, .5f, 8f)
        faixa("painel.espessuras.traco_grande", e.tracoGrande, .5f, 6f)
        faixa("painel.espessuras.traco_medio", e.tracoMedio, .2f, 4f)
        faixa("painel.espessuras.traco_fino", e.tracoFino, .1f, 2f)
        faixa("painel.espessuras.ponteiro_base", e.ponteiroBase, .1f, 5f)
        faixa("painel.espessuras.ponteiro_ponta", e.ponteiroPonta, .1f, 5f)
        faixa("painel.espessuras.contorno_numeros", e.contornoNumeros, 0f, 5f)
        faixa("painel.espessuras.agua", e.agua, 1f, 10f)
        faixa("painel.espessuras.alerta", e.alerta, .5f, 4f)
        faixa("painel.ponteiro.brilho", p.ponteiro.brilho, 0f, 1f)
        faixa("painel.ponteiro.sombra", p.ponteiro.sombra, 0f, 1f)
        faixa("painel.ponteiro.raio_inicio", p.ponteiro.raioInicio, 0f, 95f)
        faixa("painel.ponteiro.raio_ponta", p.ponteiro.raioPonta, 0f, 95f)
        if (p.ponteiro.raioInicio >= p.ponteiro.raioPonta)
            throw TemaInvalido("painel.ponteiro.raio_inicio deve ser menor que raio_ponta")
        if (p.escala.niveis != 2 && p.escala.niveis != 3) throw TemaInvalido("painel.escala.niveis fora da faixa (2 ou 3): ${p.escala.niveis}")
        faixa("painel.raios.miolo", p.raios.miolo, 20f, 60f)
        faixa("painel.raios.rotulo", p.raios.rotulo, 0f, 24f)
        faixa("painel.raios.cartao", p.raios.cartao, 0f, 24f)
        faixa("painel.animacao.rapidez", p.animacao.rapidez, 1f, 20f)
        if (p.fonte.web.familia.isBlank() || p.fonte.web.familia.length > 100 || !FORMATO_FONTE.matches(p.fonte.web.familia))
            throw TemaInvalido("painel.fonte.web.familia inválida: só letras, números, espaço, vírgula, aspas e hífen")
        for ((peso, a) in p.fonte.web.arquivos) arquivo("painel.fonte.web.arquivos.$peso", a, setOf("woff2"), arquivos)
        p.fonte.app.arquivo?.let { arquivo("painel.fonte.app.arquivo", it, setOf("ttf"), arquivos) }
        t.mascote?.let { m ->
            arquivo("mascote.imagem", m.imagem, LimitesTema.IMAGENS, arquivos)
            if ((m.nome?.length ?: 0) > 40) throw TemaInvalido("mascote.nome grande demais (máximo 40 caracteres)")
        }
        conferirPeles(p.peles, arquivos)
        val c = contraste(p.cores.texto, p.cores.fundo)
        if (c < 4.5) throw TemaInvalido("contraste baixo entre painel.cores.texto e painel.cores.fundo: %.1f:1 (mínimo 4,5:1)".format(c).replace('.', ','))
    }

    private fun inteiroNaFaixa(nome: String, v: Int, lo: Int, hi: Int) {
        if (v < lo || v > hi) throw TemaInvalido("$nome fora da faixa ($lo a $hi): $v")
    }

    /** Faixas e arquivos das peles (docs/TEMA.md). */
    private fun conferirPeles(p: Peles, arquivos: Set<String>) {
        val img = LimitesTema.IMAGENS
        fun ref(nome: String, v: String?, exts: Set<String>) { if (v != null) arquivo(nome, v, exts, arquivos) }
        val m = p.mostrador
        val n = "painel.peles"
        faixa("$n.mostrador.angulo_inicio", m.anguloInicio, -180f, 360f)
        faixa("$n.mostrador.angulo_fim", m.anguloFim, -180f, 360f)
        if (m.anguloInicio == m.anguloFim) throw TemaInvalido("$n.mostrador.angulo_inicio e angulo_fim não podem ser iguais")
        ref("$n.mostrador.imagens.fundo", m.imagens.fundo, img)
        ref("$n.mostrador.imagens.miolo", m.imagens.miolo, img)
        ref("$n.mostrador.imagens.vidro", m.imagens.vidro, img)
        ref("$n.mostrador.imagens.mascara_arco", m.imagens.mascaraArco, img)
        m.imagens.agulha?.let { a ->
            ref("$n.mostrador.imagens.agulha.arquivo", a.arquivo, img)
            if (a.pivo.size != 2) throw TemaInvalido("$n.mostrador.imagens.agulha.pivo deve ter 2 números")
            a.pivo.forEachIndexed { i, x -> faixa("$n.mostrador.imagens.agulha.pivo[$i]", x, 0f, 1f) }
            faixa("$n.mostrador.imagens.agulha.comprimento", a.comprimento, .1f, 1f)
        }
        m.arco?.let { a ->
            faixa("$n.mostrador.arco.raio", a.raio, 20f, 98f)
            faixa("$n.mostrador.arco.espessura", a.espessura, 1f, 30f)
            inteiroNaFaixa("$n.mostrador.arco.segmentos", a.segmentos, 8, 120)
            if (a.estilo != "continuo" && a.estilo != "segmentos")
                throw TemaInvalido("$n.mostrador.arco.estilo: valor fora da lista (${a.estilo}); use continuo, segmentos")
        }
        m.digital?.let { d ->
            d.posicao?.let { if (it.size != 2) throw TemaInvalido("$n.mostrador.digital.posicao deve ter 2 números") }
            d.tamanho?.let { faixa("$n.mostrador.digital.tamanho", it, 6f, 80f) }
            faixa("$n.mostrador.digital.brilho", d.brilho, 0f, 1f)
            ref("$n.mostrador.digital.fonte", d.fonte, setOf("woff2"))
            ref("$n.mostrador.digital.fonte_app", d.fonteApp, setOf("ttf"))
        }
        conferirCoresDaPele("$n.mostrador.cores", m.cores)
        val b = p.barra
        if (b.estilo != "continua" && b.estilo != "segmentos")
            throw TemaInvalido("$n.barra.estilo: valor fora da lista (${b.estilo}); use continua, segmentos")
        inteiroNaFaixa("$n.barra.segmentos", b.segmentos, 4, 60)
        faixa("$n.barra.espaco", b.espaco, 0f, .6f)
        b.raio?.let { faixa("$n.barra.raio", it, 0f, 12f) }
        ref("$n.barra.imagens.trilho", b.imagens.trilho, img)
        ref("$n.barra.imagens.cheia", b.imagens.cheia, img)
        ref("$n.numero.fonte", p.numero.fonte, setOf("woff2")); ref("$n.numero.fonte_app", p.numero.fonteApp, setOf("ttf"))
        faixa("$n.numero.brilho", p.numero.brilho, 0f, 1f)
        faixa("$n.numero_historico.linha", p.numeroHistorico.linha, .5f, 4f)
        ref("$n.luz.imagens.apagada", p.luz.imagens.apagada, img); ref("$n.luz.imagens.acesa", p.luz.imagens.acesa, img)
        faixa("$n.luz.brilho", p.luz.brilho, 0f, 1f)
        ref("$n.estado.fonte", p.estado.fonte, setOf("woff2")); ref("$n.estado.fonte_app", p.estado.fonteApp, setOf("ttf"))
        ref("$n.falhas.fonte", p.falhas.fonte, setOf("woff2")); ref("$n.falhas.fonte_app", p.falhas.fonteApp, setOf("ttf"))
    }

    /** `peles.mostrador.cores`: as mesmas regras de `painel.cores` (chaves já conferidas): cor válida e listas do mesmo tamanho. */
    private fun conferirCoresDaPele(caminho: String, o: JsonObject) {
        for ((k, v) in o) {
            val n = TAMANHO_LISTAS_CORES[k]
            if (n == null) { cor("$caminho.$k", v); continue }
            if (v !is JsonArray) throw TemaInvalido("$caminho.$k deve ser uma lista de $n cores")
            if (v.size != n) throw TemaInvalido("$caminho.$k deve ter $n cores (tem ${v.size})")
            v.forEachIndexed { i, x -> cor("$caminho.$k[$i]", x) }
        }
    }

    /** A `interface` é do painel web; aqui só se confere o que o contrato manda (cores, fontes, raios, contraste). */
    private fun conferirInterface(i: JsonElement) {
        val o = i as? JsonObject ?: throw TemaInvalido("interface deve ser um objeto")
        for (modo in listOf("escuro", "claro")) {
            val m = o[modo] as? JsonObject ?: throw TemaInvalido("interface.$modo deve ser um objeto")
            for ((k, v) in m) cor("interface.$modo.$k", v)
            val ink = corDeTexto((m["ink"] as JsonPrimitive).content)!!
            val bg = corDeTexto((m["bg"] as JsonPrimitive).content)!!
            val c = contraste(ink, bg)
            if (c < 4.5) throw TemaInvalido("contraste baixo entre interface.$modo.ink e bg: %.1f:1 (mínimo 4,5:1)".format(c).replace('.', ','))
        }
        (o["fontes"] as? JsonObject)?.forEach { (k, v) ->
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (s == null || s.isBlank() || s.length > 300 || !FORMATO_FONTE.matches(s))
                throw TemaInvalido("interface.fontes.$k inválida: só letras, números, espaço, vírgula, aspas e hífen")
        }
        (o["raios"] as? JsonObject)?.forEach { (k, v) ->
            val n = (v as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toFloatOrNull()
            if (n == null || n < 0f || n > 24f) throw TemaInvalido("interface.raios.$k fora da faixa (0 a 24)")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pacote (.zip): tema.json na raiz e arquivos/ opcional

private fun lerLimitado(entrada: InputStream, limite: Int, nome: String): ByteArray {
    val saida = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = entrada.read(buf)
        if (n < 0) break
        total += n
        if (total > limite) throw TemaInvalido("$nome grande demais (máximo ${limite / 1024} kB)")
        saida.write(buf, 0, n)
    }
    return saida.toByteArray()
}

/** Lê o .zip respeitando os limites do contrato; o tamanho é o que de fato se lê, não o que o cabeçalho diz. */
fun lerPacoteZip(entrada: InputStream): Pacote {
    var json: String? = null
    val arquivos = linkedMapOf<String, ByteArray>()
    var total = 0
    var entradas = 0
    try {
        ZipInputStream(entrada).use { z ->
            while (true) {
                val e: ZipEntry = z.nextEntry ?: break
                val nome = e.name
                if (++entradas > LimitesTema.ENTRADAS) throw TemaInvalido("arquivos demais no .zip (máximo ${LimitesTema.ENTRADAS})")
                if (nome.startsWith("/") || '\\' in nome || nome.split('/').any { it == ".." } || '\u0000' in nome)
                    throw TemaInvalido("caminho proibido no .zip: $nome")
                if (e.isDirectory) {
                    if (nome != "arquivos/") throw TemaInvalido("pasta proibida no .zip: $nome")
                    continue
                }
                when {
                    nome == "tema.json" -> {
                        if (json != null) throw TemaInvalido("tema.json repetido no .zip")
                        val b = lerLimitado(z, LimitesTema.JSON, "tema.json")
                        total += b.size
                        json = try {
                            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString()
                        } catch (_: java.nio.charset.CharacterCodingException) {
                            throw TemaInvalido("tema.json não está em UTF-8")
                        }
                    }
                    caminhoDeArquivoValido(nome) -> {
                        if (nome in arquivos) throw TemaInvalido("arquivo repetido no .zip: $nome")
                        val b = lerLimitado(z, LimitesTema.POR_ARQUIVO, nome)
                        total += b.size
                        if (total > LimitesTema.TOTAL) throw TemaInvalido("o tema passa de ${LimitesTema.TOTAL / 1024 / 1024} MB no total")
                        arquivos[nome] = b
                    }
                    else -> throw TemaInvalido("arquivo não permitido no .zip: $nome (só tema.json e arquivos/*.woff2, ttf, png, webp, jpg)")
                }
            }
        }
    } catch (e: java.util.zip.ZipException) {
        throw TemaInvalido("o .zip está corrompido: ${e.message}")
    }
    return Pacote(json ?: throw TemaInvalido("falta o tema.json na raiz do .zip"), arquivos)
}

/** Escreve o pacote no formato do contrato (tema.json na raiz, arquivos em arquivos/). */
fun escreverPacoteZip(saida: OutputStream, p: Pacote) {
    ZipOutputStream(saida).use { z ->
        z.putNextEntry(ZipEntry("tema.json")); z.write(p.json.toByteArray(Charsets.UTF_8)); z.closeEntry()
        for ((nome, b) in p.arquivos) { z.putNextEntry(ZipEntry(nome)); z.write(b); z.closeEntry() }
    }
}

/** Zip tem a assinatura `PK\u0003\u0004` no começo. */
fun ehZip(b: ByteArray) = b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()
