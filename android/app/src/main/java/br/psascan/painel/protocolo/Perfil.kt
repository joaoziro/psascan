package br.psascan.painel.protocolo

import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.pow

/*
 * Perfil de central (assets/perfil.json, o mesmo perfis/<id>/perfil.json da versão Linux): como
 * conectar, o que ler, como converter os bytes e as faixas de referência. Formato em docs/PERFIL.md.
 * É só dados; quem fala com a central é a família de protocolo (Psa5.kt).
 */

/** O perfil não segue o formato (campo faltando, byte que não é hexadecimal, referência que não existe…). */
class PerfilInvalido(msg: String) : Exception(msg)

@Serializable
data class Perfil(
    val formato: Int,
    val id: String,
    val nome: String,
    val compatibilidade: List<String>,
    val scanner: String,
    val familia: String,
    val documentacao: String? = null,
    val conexao: ConfigConexao,
    val leitura: ConfigLeitura,
    val metricas: List<Metrica>,
    val curvas: Map<String, Curva> = emptyMap(),
    val condicoes: Map<String, Condicao>,
    val falhas: ConfigFalhas,
    val escrita: List<Escrita> = emptyList(),
) {
    fun metrica(ordem: Int) = metricas.first { it.ordem == ordem }
    fun metricaPorId(id: String) = metricas.firstOrNull { it.id == id }
    fun regras(ordem: Int) = metrica(ordem).faixas

    /** A métrica que a análise de lambda (trocas rico↔pobre) avalia. */
    val lambda: Metrica? get() = metricas.firstOrNull { it.analise == "lambda" }

    /** A métrica que é a luz de aviso de falha. */
    val luzFalha: Metrica? get() = metricas.firstOrNull { it.luzFalha }

    /** Comando de apagar falhas, se o perfil tiver um validado. */
    val apagarFalhas: Escrita? get() = escrita.firstOrNull { it.tipo == "apagar_falhas" }

    /** Blocos que as métricas usam, na ordem em que o perfil os lista. */
    val blocos get() = leitura.blocos

    /** Bloco da métrica de lambda (o que se lê mais vezes numa gravação), senão o primeiro. */
    val blocoPrincipal: Int get() = lambda?.bloco ?: leitura.blocos.first().id

    /**
     * d = resposta do bloco já sem cabeçalho e checksum, com o eco: `byte` conta a partir de d[0].
     * Lança IndexOutOfBoundsException se a resposta for curta demais.
     */
    fun converter(m: Metrica, d: ByteArray): Valor {
        val u = { k: Int -> d[k].toInt() and 0xFF }
        return when (m.tipo) {
            "bit" -> {
                val v = (u(m.byte) shr (m.bit ?: 0)) and 1
                Valor(m, v, v.toDouble(), m.valores[v.toString()] ?: "?")
            }
            "enum" -> {
                val v = u(m.byte)
                Valor(m, v, v.toDouble(), m.valores[v.toString()] ?: "?")
            }
            else -> {
                val raw = if (m.tipo == "u16") u(m.byte) shl 8 or u(m.byte + 1) else u(m.byte)
                val n = numero(m, raw)
                val txt = String.format(Locale.ROOT, "%.${m.decimais}f", n)
                Valor(m, raw, n, if (m.unidade.isEmpty()) txt else "$txt ${m.unidade}")
            }
        }
    }

    /** O número que a métrica vale para um valor bruto. */
    fun numero(m: Metrica, raw: Int): Double {
        if (m.tipo == "bit" || m.tipo == "enum") return raw.toDouble()
        if (m.tipo == "curva") {
            val c = curvas.getValue(m.curva!!)
            val s = c.coef.withIndex().sumOf { (n, k) -> k * raw.toDouble().pow(n) }
            return s.coerceIn(c.min, c.max)
        }
        return raw * m.fator + m.offset
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Lê e valida; qualquer problema vira [PerfilInvalido] com o motivo. */
        fun de(texto: String): Perfil {
            val p = try {
                json.decodeFromString(serializer(), texto)
            } catch (e: SerializationException) {
                throw PerfilInvalido("perfil ilegível: ${e.message?.lineSequence()?.first()}")
            } catch (e: IllegalArgumentException) {
                throw PerfilInvalido("perfil ilegível: ${e.message?.lineSequence()?.first()}")
            }
            p.validar()
            return p
        }
    }
}

@Serializable
data class ConfigConexao(
    val pino: String,
    val destravar: String? = null,
    val linha: String,
    val inicio: String,
    @SerialName("inicio_tentativas") val inicioTentativas: Int = 3,
    val acordar: String? = null,
    @SerialName("acordar_timeout_s") val acordarTimeoutS: Double = 6.0,
    val manter: String,
    @SerialName("manter_apos_s") val manterAposS: Double = 1.0,
    val descricao: String = "",
)

@Serializable
data class BlocoLeitura(val id: Int, val pedido: String)

@Serializable
data class LeituraFalhas(val pedido: String, val decodificacao: String, @SerialName("sem_resposta") val semResposta: String)

@Serializable
data class ConfigLeitura(val blocos: List<BlocoLeitura>, val falhas: LeituraFalhas)

@Serializable
data class Curva(
    val tipo: String, val coef: List<Double>, val min: Double, val max: Double,
    val nivel: String = "pendente", val nota: String? = null,
)

@Serializable
data class Metrica(
    val id: String,
    val ordem: Int,
    val curto: String,
    val nome: String,
    val unidade: String = "",
    val bloco: Int,
    val byte: Int,
    val tipo: String,
    val fator: Double = 1.0,
    val offset: Double = 0.0,
    val decimais: Int = 0,
    val curva: String? = null,
    val bit: Int? = null,
    val valores: Map<String, String> = emptyMap(),
    @SerialName("valores_pendentes") val valoresPendentes: List<String> = emptyList(),
    val escala: List<Double>? = null,
    val faixas: List<Regra> = emptyList(),
    val obs: String? = null,
    val analise: String? = null,
    @SerialName("luz_falha") val luzFalha: Boolean = false,
    val nivel: String = "pendente",
    val evidencia: String? = null,
) {
    /** Bit ou enum: o valor é uma escolha da lista `valores`, não uma grandeza com unidade. */
    val ehDiscreto get() = tipo == "bit" || tipo == "enum"
}

@Serializable
data class Regra(
    val quando: String,
    val normal: List<Double>? = null,
    val atencao: List<Double>? = null,
    val abaixo: String? = null,
    val acima: String? = null,
    val nota: String? = null,
    @SerialName("normal_valor") val normalValor: Double? = null,
    val msg: String? = null,
)

@Serializable
data class RegraCondicao(val condicao: String? = null, val metrica: String? = null, val op: String? = null, val valor: Double? = null)

@Serializable
data class Condicao(val texto: String = "", val rotulo: String = "", val regras: List<RegraCondicao> = emptyList())

@Serializable
data class TipoFalha(val nome: String, val nivel: String = "pendente")

@Serializable
data class CodigoFalha(val nome: String, val nivel: String = "pendente", val causas: List<String> = emptyList())

@Serializable
data class ConfigFalhas(val tipos: Map<String, TipoFalha> = emptyMap(), val codigos: Map<String, CodigoFalha> = emptyMap())

/** Comando que muda o estado do carro: só roda com a escrita habilitada e confirmada (ver Psa5.escrever). */
@Serializable
data class Escrita(
    val id: String,
    val tipo: String,
    val nome: String,
    val pedido: String,
    val antes: List<String> = emptyList(),
    val perigoso: Boolean = false,
    val aviso: String = "",
    val nivel: String = "pendente",
)

/** Uma leitura: valor bruto, número (para os mostradores) e texto formatado. */
data class Valor(val m: Metrica, val raw: Int?, val numero: Double?, val texto: String)

private val HEX = Regex("^[0-9A-Fa-f]{2}( [0-9A-Fa-f]{2})*$")
private val ID_PERFIL = Regex("^[a-z0-9-]+$")
private val ID_METRICA = Regex("^[a-z0-9_]+$")
private val TIPOS = setOf("u8", "u16", "bit", "enum", "curva")
private val NIVEIS = setOf("V1", "V2", "V3", "pendente")
private val OPS = setOf(">", ">=", "<", "<=", "==", "!=")

/** "13 07 03 00" → bytes. */
fun String.bytesHex(): ByteArray = split(" ").map { it.toInt(16).toByte() }.toByteArray()

private fun Perfil.validar() {
    fun erro(msg: String): Nothing = throw PerfilInvalido(msg)
    fun hex(onde: String, s: String?) {
        if (s == null || !HEX.matches(s)) erro("$onde: \"${s ?: ""}\" não são bytes em hexadecimal separados por espaço")
    }
    fun nivel(onde: String, n: String) { if (n !in NIVEIS) erro("$onde: nível \"$n\" inválido (use V1, V2, V3 ou pendente)") }

    if (formato != 1) erro("formato $formato não suportado (este app lê o formato 1)")
    if (!ID_PERFIL.matches(id)) erro("id \"$id\" inválido (só a-z, 0-9 e -)")
    if (nome.isBlank()) erro("nome vazio")
    if (scanner != "psa-com") erro("scanner \"$scanner\" não suportado (só psa-com)")
    if (familia != "psa5") erro("família \"$familia\" não suportada (só psa5)")

    conexao.run {
        hex("conexao.pino", pino); hex("conexao.linha", linha); hex("conexao.inicio", inicio); hex("conexao.manter", manter)
        if (destravar != null) hex("conexao.destravar", destravar)
        if (acordar != null) hex("conexao.acordar", acordar)
        if (inicioTentativas < 1) erro("conexao.inicio_tentativas precisa ser 1 ou mais")
        if (acordarTimeoutS <= 0 || manterAposS < 0) erro("conexao: tempos inválidos")
    }

    if (leitura.blocos.isEmpty()) erro("leitura.blocos vazio")
    if (leitura.blocos.map { it.id }.toSet().size != leitura.blocos.size) erro("leitura.blocos: id repetido")
    leitura.blocos.forEach { hex("leitura.blocos[${it.id}].pedido", it.pedido) }
    hex("leitura.falhas.pedido", leitura.falhas.pedido)
    hex("leitura.falhas.sem_resposta", leitura.falhas.semResposta)
    if (leitura.falhas.decodificacao != "psa5-pares") erro("leitura.falhas.decodificacao \"${leitura.falhas.decodificacao}\" desconhecida")

    if (metricas.isEmpty()) erro("metricas vazio")
    if (metricas.map { it.id }.toSet().size != metricas.size) erro("metricas: id repetido")
    if (metricas.map { it.ordem }.toSet().size != metricas.size) erro("metricas: ordem repetida")
    val blocosIds = leitura.blocos.map { it.id }.toSet()
    val temSempre = { q: String -> q == "sempre" || q in condicoes }
    for (m in metricas) {
        val o = "metrica ${m.id}"
        if (!ID_METRICA.matches(m.id)) erro("$o: id inválido (só a-z, 0-9 e _)")
        if (m.bloco !in blocosIds) erro("$o: bloco ${m.bloco} não existe em leitura.blocos")
        if (m.byte < 0) erro("$o: byte negativo")
        if (m.tipo !in TIPOS) erro("$o: tipo \"${m.tipo}\" desconhecido")
        if (m.decimais < 0) erro("$o: decimais negativo")
        when (m.tipo) {
            "curva" -> {
                val c = m.curva ?: erro("$o: tipo curva precisa do campo curva")
                if (c !in curvas) erro("$o: curva \"$c\" não existe em curvas")
            }
            "bit" -> {
                if (m.bit == null || m.bit !in 0..7) erro("$o: tipo bit precisa de bit entre 0 e 7")
                if (m.valores.isEmpty()) erro("$o: tipo bit precisa de valores")
            }
            "enum" -> if (m.valores.isEmpty()) erro("$o: tipo enum precisa de valores")
        }
        for (k in m.valores.keys) if (k.toIntOrNull() == null) erro("$o: chave \"$k\" de valores não é número")
        for (k in m.valoresPendentes) if (k !in m.valores) erro("$o: valores_pendentes \"$k\" não está em valores")
        m.escala?.let { if (it.size != 2 || it[0] >= it[1]) erro("$o: escala precisa ser [mín, máx] com mín < máx") }
        if (m.analise != null && m.analise != "lambda") erro("$o: análise \"${m.analise}\" desconhecida")
        nivel(o, m.nivel)
        for (r in m.faixas) {
            if (!temSempre(r.quando)) erro("$o: faixa usa a condição \"${r.quando}\", que não existe")
            if (r.normalValor == null && (r.normal == null || r.normal.size != 2)) erro("$o: faixa precisa de normal [mín, máx] ou normal_valor")
            if (r.atencao != null && r.atencao.size != 2) erro("$o: atencao precisa ser [mín, máx]")
        }
    }

    for ((nome, c) in curvas) {
        if (c.tipo != "polinomio") erro("curva $nome: tipo \"${c.tipo}\" desconhecido")
        if (c.coef.isEmpty()) erro("curva $nome: sem coeficientes")
        if (c.min >= c.max) erro("curva $nome: min precisa ser menor que max")
        nivel("curva $nome", c.nivel)
    }

    val ids = metricas.map { it.id }.toSet()
    for ((nome, c) in condicoes) for (r in c.regras) {
        when {
            r.condicao != null -> if (!temSempre(r.condicao)) erro("condição $nome: usa \"${r.condicao}\", que não existe")
            r.metrica != null -> {
                if (r.metrica !in ids) erro("condição $nome: métrica \"${r.metrica}\" não existe")
                if (r.op !in OPS) erro("condição $nome: operador \"${r.op}\" inválido")
                if (r.valor == null) erro("condição $nome: regra de métrica sem valor")
            }
            else -> erro("condição $nome: regra precisa de condicao ou metrica")
        }
    }
    // condição que depende de si mesma, direta ou indiretamente, nunca terminaria
    fun ciclo(nome: String, caminho: List<String>) {
        if (nome in caminho) erro("condição ${caminho.first()}: referência circular (${(caminho + nome).joinToString(" → ")})")
        condicoes[nome]?.regras?.forEach { r -> r.condicao?.let { ciclo(it, caminho + nome) } }
    }
    condicoes.keys.forEach { ciclo(it, emptyList()) }

    for ((k, t) in falhas.tipos) { if (k.toIntOrNull() == null) erro("falhas.tipos: chave \"$k\" não é número"); nivel("tipo de falha $k", t.nivel) }
    for ((k, c) in falhas.codigos) { if (k.toIntOrNull() == null) erro("falhas.codigos: chave \"$k\" não é número"); nivel("falha $k", c.nivel) }

    if (escrita.map { it.id }.toSet().size != escrita.size) erro("escrita: id repetido")
    for (e in escrita) {
        val o = "escrita ${e.id}"
        if (e.tipo != "apagar_falhas" && e.tipo != "atuador") erro("$o: tipo \"${e.tipo}\" desconhecido")
        hex("$o.pedido", e.pedido)
        e.antes.forEach { hex("$o.antes", it) }
        nivel(o, e.nivel)
        if (e.nivel == "pendente") erro("$o: só entram comandos com captura (nível V1 ou mais)")
    }
}
