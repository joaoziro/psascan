package br.psascan.painel.protocolo

/** Canal de bytes até o scanner: Bluetooth de verdade ou o simulador. */
interface Transporte {
    fun escrever(dados: ByteArray)

    /** Bytes que chegarem em até [timeoutMs]; vazio = nada chegou. Lança ErroLink se a conexão fechou. */
    fun ler(timeoutMs: Long): ByteArray

    fun fechar()
}

/** O que o dongle.py faz com o socket: envia um quadro e espera a resposta do mesmo comando. */
class Scanner(private val t: Transporte, private val log: (String) -> Unit = {}) {
    private var buf = ByteArray(0)

    fun nota(texto: String) = log(texto)

    private fun descartarPendente() {
        while (true) {
            val b = t.ler(0)
            if (b.isEmpty()) break
            log("descartado: " + b.hex())
        }
    }

    /** okSeteF00: aceita `7F <cmd> 00` como sucesso (assim o dongle confirma 13 e 07). */
    fun transact(payload: ByteArray, timeoutMs: Long = 2000, okSeteF00: Boolean = false): ByteArray {
        val q = Dongle.frame(payload)
        descartarPendente()
        buf = ByteArray(0)
        log("TX: " + q.hex())
        t.escrever(q)
        val fim = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val (p, resto) = Dongle.unframe(buf)
            buf = resto
            if (p != null) {
                log("RX: " + Dongle.frame(p, true).hex())
                val c0 = p[0].toInt() and 0xFF
                if (okSeteF00 && p.size > 2 && c0 == 0x7F && p[1] == payload[0] && p[2].toInt() == 0) return p
                if (c0 == 0x7F || c0 != (payload[0].toInt() and 0xFF) + 0x80) throw ErroDongle(p)
                return p
            }
            val resta = (fim - System.nanoTime()) / 1_000_000
            if (resta <= 0) throw SemResposta("sem resposta (recebido: ${buf.hex()})")
            buf += t.ler(resta)
        }
    }

    data class Versao(val hw: Int?, val firmware: String?, val bruto: String)

    fun versao(): Versao {
        val p = transact(bytesDe(Dongle.VERSAO), 1000)
        val u = { i: Int -> p[i].toInt() and 0xFF }
        return Versao(if (p.size > 1) u(1) else null, if (p.size > 4) "${u(3)}.${u(4)}" else null, p.hex())
    }

    fun fechar() = t.fechar()
}
