package br.psascan.painel.protocolo

import java.io.IOException

/*
 * Quadros do scanner "PSA COM" e da família PSA 5 de centrais — o mesmo de psascan/dongle.py e sagem.py.
 * Os comandos específicos de cada central (pino, linha K, blocos…) ficam no perfil, não aqui.
 *
 * Envelope do dongle, nos dois sentidos:
 *     [0x80 | 0xA0+len_hi] [len_lo] payload... [crc_lo] [crc_hi]
 * CRC-16/X-25 sobre cabeçalho + payload. payload[0] é o comando; na resposta 0x7F = erro.
 *
 * Quadro para a central (dentro do comando 04 do dongle, modo 1):
 *     [0x81+n] dados(n) [0xFF - soma]      resposta: [0xC0+n] dados(n-1) [0xFF - soma]
 */

/** Falhas de comunicação (Bluetooth caiu, scanner travou, central calada): disparam a reconexão. */
open class ErroLink(msg: String) : IOException(msg)
class SemResposta(msg: String) : ErroLink(msg)
class ErroDongle(val resposta: ByteArray) : ErroLink("dongle respondeu erro: " + resposta.hex())
class QuadroInvalido(msg: String) : ErroLink(msg)

fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
fun bytesDe(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
private val Byte.u get() = toInt() and 0xFF

object Dongle {
    const val VERSAO = 0x18   // versão/estado do dongle; não fala com a central
    const val LINHA = 0x07    // configura a linha K
    const val ECU = 0x04      // 04 <modo> <len> <bytes>: envia à central e devolve a resposta

    fun crc16X25(dados: ByteArray, n: Int = dados.size): ByteArray {
        var c = 0xFFFF
        for (k in 0 until n) {
            val b = dados[k].u
            for (i in 0 until 8) {
                if (((b shr i) xor c) and 1 != 0) c = c xor 0x10810
                c = c shr 1
            }
        }
        c = c.inv() and 0xFFFF
        return bytesDe(c and 0xFF, c shr 8)
    }

    fun frame(payload: ByteArray, resposta: Boolean = false): ByteArray {
        val n = payload.size
        require(n in 1..0x1FFF && (payload[0].u < 0x80 || resposta)) { "payload inválido" }
        val hdr = if (n <= 0xFF) bytesDe(0x80, n) else bytesDe(0xA0 or (n shr 8), n and 0xFF)
        val corpo = hdr + payload
        return corpo + crc16X25(corpo)
    }

    /** (payload, resto) ou (null, buf) se o quadro ainda não chegou inteiro. */
    fun unframe(buf: ByteArray): Pair<ByteArray?, ByteArray> {
        if (buf.size < 2) return null to buf
        if (buf[0].u != 0x80 && buf[0].u and 0xF0 != 0xA0)
            throw QuadroInvalido("cabeçalho inválido: " + buf.copyOf(minOf(4, buf.size)).hex())
        val n = (buf[0].u and 0x1F) shl 8 or buf[1].u
        if (buf.size < n + 4) return null to buf
        val crc = crc16X25(buf, n + 2)
        if (crc[0] != buf[n + 2] || crc[1] != buf[n + 3])
            throw QuadroInvalido("CRC inválido: " + buf.copyOf(n + 4).hex())
        return buf.copyOfRange(2, n + 2) to buf.copyOfRange(n + 4, buf.size)
    }
}

object Sagem {
    fun quadro(dados: ByteArray): ByteArray {
        val q = byteArrayOf((0x81 + dados.size).toByte()) + dados
        return q + ((0xFF - q.sumOf { it.u }) and 0xFF).toByte()
    }

    /** Só os dados. base = 0xC0 para respostas, 0x80 para pedidos. */
    fun desquadro(r: ByteArray, base: Int = 0xC0): ByteArray {
        val n = r[0].u - base
        if (n < 1 || n > r.size - 1) throw QuadroInvalido("cabeçalho inválido: " + r.hex())
        var soma = 0
        for (k in 0 until n) soma += r[k].u
        if ((0xFF - soma) and 0xFF != r[n].u) throw QuadroInvalido("checksum inválido: " + r.hex())
        return r.copyOfRange(1, n)
    }

    fun paraDongle(dados: ByteArray): ByteArray {
        val q = quadro(dados)
        return bytesDe(Dongle.ECU, 0x01, q.size) + q
    }

    data class Falha(val codigo: Int, val tipo: Int, val presente: Boolean)

    /**
     * Decodificação "psa5-pares". dados = resposta sem cabeçalho/checksum; dados[0] = eco do pedido.
     * semResposta = primeiro byte que indica "sem resposta válida" (perfil.leitura.falhas). null = sem resposta válida.
     */
    fun falhas(dados: ByteArray, semResposta: Int): List<Falha>? {
        if (dados.isNotEmpty() && dados[0].u == semResposta) return null
        val out = mutableListOf<Falha>()
        var i = 1
        while (i < dados.size - 1) {
            val v = dados[i].u shl 8 or dados[i + 1].u
            if (v != 0) out += Falha((v shr 3) and 0x1FF, dados[i + 1].u and 7, v and 0x2000 == 0)
            i += 2
        }
        return out
    }
}
