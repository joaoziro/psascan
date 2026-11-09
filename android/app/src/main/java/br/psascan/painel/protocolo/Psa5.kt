package br.psascan.painel.protocolo

/** Tentou um comando de escrita com a escrita desabilitada (o app começa sempre em somente leitura). */
class EscritaBloqueada(msg: String) : IllegalStateException(msg)

/**
 * Família de protocolo PSA 5 (psascan/sl96.py): conexão pelo scanner, leitura de blocos e de falhas.
 * Todos os bytes vêm do perfil. Só lê; os comandos de `perfil.escrita` exigem [escritaHabilitada].
 */
class Psa5(
    private val s: Scanner, val perfil: Perfil,
    /** Consultado a cada [escrever]: a escrita só vale enquanto o usuário a mantiver ligada. */
    private val escritaHabilitada: () -> Boolean = { false },
) {
    private val c = perfil.conexao
    private val pino = c.pino.bytesHex()
    private val linha = c.linha.bytesHex()
    private val inicio = c.inicio.bytesHex()
    private val manter = c.manter.bytesHex()
    private val semResposta = perfil.leitura.falhas.semResposta.bytesHex()[0].toInt() and 0xFF
    private var ultimo = 0L

    private fun ecu(desc: String, dados: ByteArray, timeoutMs: Long = 1500): ByteArray {
        s.nota(desc)
        val r = s.transact(Sagem.paraDongle(dados), timeoutMs)
        if (r[0].toInt() and 0xFF != 0x84) throw ErroLink("resposta inesperada do dongle: " + r.hex())
        ultimo = System.nanoTime()
        return Sagem.desquadro(r.copyOfRange(1, r.size))
    }

    /**
     * versão → pino (se `7F <cmd> 07`: destravar e repetir) → linha K → início até N vezes (para na
     * primeira resposta) → se calada, acordar (init 5 baud).
     */
    fun conectar(etapa: (String) -> Unit = {}): Scanner.Versao {
        etapa("versão do scanner")
        val v = s.versao()
        etapa("linha K")
        try {
            s.transact(pino, okSeteF00 = true)
        } catch (e: ErroDongle) {          // motor ligado: o scanner recusa o pino até receber o destravar
            val destravar = c.destravar
            if (destravar == null || e.resposta.size < 3 || e.resposta[1] != pino[0] || e.resposta[2].toInt() != 0x07) throw e
            s.nota("pino recusado; destravar e repetir")
            s.transact(destravar.bytesHex(), 2000)
            s.transact(pino, okSeteF00 = true)
        }
        etapa("configurar linha K")
        s.transact(linha, okSeteF00 = true)
        etapa("início de comunicação")
        var ok = false
        for (k in 0 until c.inicioTentativas) {   // depois que o 1º respondeu, repetir dava erro no carro
            try {
                ecu("início de comunicação", inicio); ok = true; break
            } catch (e: ErroLink) {
                if (e is QuadroInvalido) throw e
                Thread.sleep(150)
            }
        }
        val acordar = c.acordar
        if (!ok && acordar != null) {      // central dormindo: init lento (~2,5 s)
            etapa("acordar central (5 baud)")
            s.transact(acordar.bytesHex(), (c.acordarTimeoutS * 1000).toLong())
            ultimo = System.nanoTime()
        } else if (!ok) throw SemResposta("a central não respondeu ao início de comunicação")
        return v
    }

    /** Manutenção se ficou mais que `manter_apos_s` sem falar com a central. */
    fun manter() {
        if (System.nanoTime() - ultimo > c.manterAposS * 1e9) ecu("manter conexão", manter)
    }

    fun lerFalhas(): List<Sagem.Falha>? =
        Sagem.falhas(ecu("ler códigos de falha", perfil.leitura.falhas.pedido.bytesHex()), semResposta)

    /** blocos: null = todos; listOf(0) = só o bloco 0, mais rápido. Devolve as métricas na ordem do perfil. */
    fun lerParametros(blocos: List<Int>? = null): List<Valor> {
        val out = mutableListOf<Valor>()
        for (b in perfil.leitura.blocos) {
            if (blocos != null && b.id !in blocos) continue
            val d = ecu("ler bloco ${b.id}", b.pedido.bytesHex())
            for (m in perfil.metricas) {
                if (m.bloco != b.id) continue
                out += try {
                    perfil.converter(m, d)
                } catch (e: IndexOutOfBoundsException) {
                    Valor(m, null, null, "(resposta curta)")
                }
            }
        }
        return out.sortedBy { it.m.ordem }
    }

    /**
     * Manda um comando de `perfil.escrita` (os `antes`, se houver, e depois o pedido). Muda o estado do
     * carro: lança [EscritaBloqueada] se a escrita não estiver habilitada. Devolve a resposta do pedido.
     */
    fun escrever(id: String): ByteArray {
        val e = perfil.escrita.firstOrNull { it.id == id } ?: throw EscritaBloqueada("o perfil não tem o comando \"$id\"")
        if (!escritaHabilitada()) throw EscritaBloqueada("comandos de escrita desabilitados (somente leitura)")
        for (a in e.antes) ecu("${e.nome}: preparar", a.bytesHex())
        return ecu("ESCREVER: ${e.nome}", e.pedido.bytesHex())
    }
}
