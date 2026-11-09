package br.psascan.painel.protocolo

import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * Scanner + central de mentira, como psascan/sim.py (cenário "estrada"): responde aos quadros de
 * verdade, então o app passa pelo mesmo caminho (envelope, checksum, conversões) que no carro.
 * Ciclo de 2 min: aquece, marcha lenta, acelera, cruzeiro, freio-motor; a luz acende no fim.
 */
class SimTransporte(private val perfil: Perfil) : Transporte {
    private val fila = ConcurrentLinkedQueue<Pair<Long, ByteArray>>()
    private var buf = ByteArray(0)
    private val t0 = System.nanoTime()
    private val rnd = Random(306)
    private val c = perfil.conexao
    private val pino = c.pino.bytesHex()
    private val linha = c.linha.bytesHex()
    private val acordar = c.acordar?.bytesHex()
    private val inicio = c.inicio.bytesHex()
    private val manter = c.manter.bytesHex()
    private val pedidoFalhas = perfil.leitura.falhas.pedido.bytesHex()
    private val apagar = perfil.apagarFalhas?.pedido?.bytesHex()
    private val atuadores = perfil.escrita.filter { it.tipo == "atuador" }.map { it.pedido.bytesHex() }
    // lidas no carro em 2026-10-25: 127h riqueza presente, 02A lambda e 029 detonação memorizadas
    private var falhas = mutableListOf(Triple(0x127, 2, true), Triple(0x2A, 1, false), Triple(0x29, 1, false))

    override fun escrever(dados: ByteArray) {
        buf += dados
        while (true) {
            val (p, resto) = Dongle.unframe(buf)
            buf = resto
            if (p == null) break
            val atraso = if (p[0].toInt() == Dongle.ECU) 30 else 5   // ~ ida e volta na linha K
            fila.add(System.nanoTime() + atraso * 1_000_000L to Dongle.frame(resposta(p), true))
        }
    }

    override fun ler(timeoutMs: Long): ByteArray {
        val limite = System.nanoTime() + max(0, timeoutMs) * 1_000_000
        while (true) {
            val agora = System.nanoTime()
            val prox = fila.peek()
            if (prox != null && prox.first <= agora) return fila.remove().second
            if (agora >= limite) return ByteArray(0)
            Thread.sleep(max(1, (min(limite, prox?.first ?: limite) - agora) / 1_000_000))
        }
    }

    override fun fechar() {}

    private fun resposta(p: ByteArray): ByteArray {
        val cmd = p[0].toInt() and 0xFF
        return when {
            cmd == Dongle.VERSAO -> bytesDe(0x98, 6, 0, 1, 2)
            acordar != null && p.contentEquals(acordar) -> bytesDe(0x87) + "SL964".toByteArray()
            p.contentEquals(pino) || p.contentEquals(linha) -> bytesDe(0x7F, cmd, 0, 0, 0, 0)
            cmd == Dongle.ECU -> {
                val r = ecu(Sagem.desquadro(p.copyOfRange(3, p.size), base = 0x80))
                val q = byteArrayOf((0xC1 + r.size).toByte()) + r
                byteArrayOf(0x84.toByte()) + q + ((0xFF - q.sumOf { it.toInt() and 0xFF }) and 0xFF).toByte()
            }
            else -> bytesDe(0x7F)
        }
    }

    /** Responde como a central: só aos pedidos que o perfil conhece; o resto é recusado (7F). */
    private fun ecu(d: ByteArray): ByteArray {
        return when {
            d.contentEquals(inicio) || d.contentEquals(manter) -> d
            d.contentEquals(pedidoFalhas) -> falhas.fold(bytesDe(pedidoFalhas[0].toInt() + 0x40)) { acc, (cod, tipo, presente) ->
                val v = (cod shl 3) or tipo or (if (presente) 0 else 0x2000)
                acc + bytesDe(v shr 8, v and 0xFF)
            }
            apagar != null && d.contentEquals(apagar) -> { falhas.clear(); bytesDe(0xC3) }
            atuadores.any { d.contentEquals(it) } -> bytesDe(0x66)
            else -> {
                val bl = perfil.leitura.blocos.firstOrNull { it.pedido.bytesHex().contentEquals(d) }
                if (bl != null) montarBloco(d, bl.id, raws((System.nanoTime() - t0) / 1e9)) else bytesDe(0x7F)
            }
        }
    }

    /** Resposta de bloco: o eco do pedido e os bytes de cada métrica do bloco no lugar que o perfil diz. */
    private fun montarBloco(pedido: ByteArray, bloco: Int, raws: Map<String, Int>): ByteArray {
        val tam = maxOf(25, perfil.metricas.maxOf { it.byte + 2 })
        val out = ByteArray(tam)
        pedido.copyInto(out)
        for (m in perfil.metricas) {
            val r = raws[m.id] ?: continue
            if (m.bloco != bloco) continue
            val i = m.byte
            when (m.tipo) {
                "bit" -> out[i] = (out[i].toInt() or ((r and 1) shl (m.bit ?: 0))).toByte()
                "u16" -> { out[i] = (r shr 8).toByte(); out[i + 1] = r.toByte() }
                else -> out[i] = r.toByte()
            }
        }
        return out
    }

    /** Inverso de Perfil.numero: o bruto que a central mandaria para o valor. */
    private fun bruto(m: Metrica, valor: Double): Int {
        if (m.ehDiscreto) return valor.toInt()
        if (m.tipo == "curva") return (0..255).minBy { abs(perfil.numero(m, it) - valor) }
        val lim = if (m.tipo == "u16") 0xFFFF else 0xFF
        return ((valor - m.offset) / m.fator).roundToInt().coerceIn(0, lim)
    }

    /** Sinais do cenário "estrada", por id de métrica; o que o perfil não tem é ignorado. */
    private fun raws(t: Double): Map<String, Int> {
        val n = { s: Double -> rnd.nextGaussian() * s }
        val agua = min(92.0, 40 + t * 0.35)
        val quente = agua >= 80
        val v = mutableMapOf("rpm" to 840.0, "bateria" to 14.0, "avanco" to 12.0, "carga" to 27.0, "pedal" to 16.0,
            "borboleta" to 16.0, "map" to 450.0, "pressao_atm" to 887.0, "agua" to agua, "ar" to 26 + min(14.0, t / 20),
            "bobina_14" to 3.38, "bobina_23" to 3.37, "injecao" to 1.55,
            "motor_passo" to if (quente) 18.0 else 80 - agua * 0.7, "velocidade" to 0.0, "rele_principal" to 1.0,
            "alim_central" to 1.0, "luz_injecao" to 0.0, "ac_termistor" to 0.0, "ac_autorizacao" to 0.0)
        var fase = "ralenti"
        val c = t % 120
        if (c in 20.0..<35.0) {
            fase = "acelera"
            val marcha = 1 + min(2, ((c - 20) / 5).toInt())
            v.putAll(mapOf("rpm" to 1400 + ((c - 20) % 5) * 700 + marcha * 100, "velocidade" to (c - 20) * 5.3, "pedal" to 0.0,
                "borboleta" to 45.0, "map" to 880.0, "carga" to 80.0, "injecao" to 6.0, "avanco" to 22.0))
        } else if (c in 35.0..<80.0) {
            fase = "cruzeiro"
            v.putAll(mapOf("rpm" to 2600 + 60 * sin(c), "velocidade" to 80 + 2 * sin(c / 3), "pedal" to 0.0, "borboleta" to 24.0,
                "map" to 600.0, "carga" to 45.0, "injecao" to 3.4, "avanco" to 32.0))
        }
        if (c >= 100) v["luz_injecao"] = 1.0
        if (c in 80.0..<95.0) {
            fase = "corte"
            v.putAll(mapOf("rpm" to max(900.0, 2600 - (c - 80) * 120), "velocidade" to max(0.0, 80 - (c - 80) * 5.3),
                "map" to 250.0, "carga" to 12.0, "injecao" to 0.0, "avanco" to 18.0))
        }
        val lam = when {
            !quente -> 450.0
            fase == "corte" -> 90.0
            fase == "acelera" -> 860.0
            else -> {
                val hz = if (fase == "ralenti") 0.6 else 1.4
                val s = sin(2 * PI * hz * t)
                475 + 375 * sign(s) * min(1.0, abs(3 * s))
            }
        }
        v["lambda"] = lam + n(15.0)
        v["rpm"] = v["rpm"]!! + n(12.0); v["bateria"] = v["bateria"]!! + n(0.04)
        v["avanco"] = v["avanco"]!! + n(1.5); v["map"] = v["map"]!! + n(6.0)
        val porId = perfil.metricas.associateBy { it.id }
        return v.filterKeys { it in porId }.mapValues { (id, x) -> bruto(porId.getValue(id), x) }
    }
}
