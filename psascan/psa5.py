"""Família de protocolo PSA "5" (Sagem SL96 e centrais parecidas), sobre o scanner PSA COM.

Quadro para a central: [0x81+n] dados(n) [0xFF - soma(cabeçalho+dados)]
Resposta:              [0xC0+n] dados(n-1) [0xFF - soma(cabeçalho+dados)]
(nos dois sentidos o n do cabeçalho conta o próprio cabeçalho). Tudo passa pelo comando 04 do
dongle, modo 1. Visto nas capturas: ver docs/PROTOCOLO-SL96.md.

Nenhum byte de comando fica aqui: pino, linha K, início, falhas, blocos e escrita vêm do perfil
(perfil.py / perfis/<id>/perfil.json). Este módulo só sabe montar o quadro e seguir a sequência.
"""
import time

import dongle, perfil as _perfil

FRASE_PERIGO = 'PERIGO'


def quadro(dados):
    q = bytes([0x81 + len(dados)]) + bytes(dados)
    return q + bytes([(0xFF - sum(q)) & 0xFF])


def desquadro(r, base=0xC0):
    """Devolve só os dados. base=0xC0 para respostas, 0x80 para pedidos."""
    n = r[0] - base
    if not 1 <= n <= len(r) - 1:
        raise ValueError('cabeçalho inválido: %s' % r.hex(' '))
    if (0xFF - sum(r[:n])) & 0xFF != r[n]:
        raise ValueError('checksum inválido: %s' % r.hex(' '))
    return r[1:n]


def para_dongle(dados):
    q = quadro(dados)
    return bytes([dongle.CMD_ECU, 0x01, len(q)]) + q


def decodificar_falhas(dados, sem_resposta=b'\x32'):
    """Decodificação `psa5-pares`. dados = resposta já sem cabeçalho/checksum; dados[0] é o eco.
    Depois do eco vêm pares big-endian: código = (v >> 3) & 0x1FF, tipo = v & 7,
    bit 0x2000 desligado = presente. None se a resposta é "sem resposta válida"."""
    if dados[:1] == sem_resposta:
        return None
    out = []
    for i in range(1, len(dados) - 1, 2):
        v = dados[i] << 8 | dados[i + 1]
        if v:
            out.append({'codigo': (v >> 3) & 0x1FF, 'tipo': v & 7,
                        'presente': not (v & 0x2000), 'bruto': '%04X' % v})
    return out


def quadros_conexao(perfil):
    """[(descrição, bytes do payload do dongle)] da sequência de conexão, sem enviar nada."""
    c = perfil.conexao
    out = [('versão do scanner', bytes([dongle.CMD_VERSAO])),
           ('linha K (escolher o pino)', c['pino'])]
    if 'destravar' in c:
        out.append(('se o scanner recusar o pino: destravar e repetir o pino', c['destravar']))
    out.append(('configurar linha K', c['linha']))
    out.append(('início da comunicação (%d×)' % c['inicio_tentativas'], para_dongle(c['inicio'])))
    if 'acordar' in c:
        out.append(('se o início não responder: acordar a central (5 baud)', c['acordar']))
    return out


def quadro_manter(perfil):
    return ('manter conexão parado, a cada ~%g s' % perfil.conexao['manter_apos_s'],
            para_dongle(perfil.conexao['manter']))


def quadro_falhas(perfil):
    return ('ler falhas', para_dongle(hex_pedido(perfil.leitura['falhas']['pedido'])))


def quadros_blocos(perfil, blocos=None):
    return [('ler bloco %d' % i, para_dongle(p)) for i, p in perfil.blocos if blocos is None or i in blocos]


def quadros_escrita(perfil, e):
    return [('pré-comando', para_dongle(_perfil.hex_bytes(a, 'antes'))) for a in e.get('antes', [])] + \
           [(('APAGAR: ' if e['tipo'] == 'apagar_falhas' else 'ACIONAR: ') + e['nome'],
             para_dongle(_perfil.hex_bytes(e['pedido'], 'pedido')))]


def hex_pedido(s):
    return _perfil.hex_bytes(s, 'pedido')


class Central:
    """Uma central da família psa5. `confirmar(descricao, bytes_ao_dongle) -> bool` é chamado antes
    de cada envio. `escrita=False` (padrão): somente leitura; `escrever()` levanta PermissionError."""

    def __init__(self, dongle, perfil, confirmar=None, escrita=False):
        self.d = dongle
        self.perfil = perfil
        self.confirmar = confirmar or (lambda desc, q: True)
        self.escrita = escrita
        self.ultimo = 0

    def _ecu(self, desc, dados, timeout=1.5):
        payload = para_dongle(dados)
        if not self.confirmar(desc, payload):
            self.d.nota('cancelado: ' + desc)
            raise KeyboardInterrupt('cancelado')
        self.d.nota(desc)
        r = self.d.transact(payload, timeout)
        if r[0] != dongle.CMD_ECU + 0x80:
            raise IOError('resposta inesperada do dongle: %s' % r.hex(' '))
        self.ultimo = time.monotonic()
        return desquadro(r[1:])

    def conectar(self):
        c = self.perfil.conexao
        v = self.d.versao()
        if not self.confirmar('configurar linha K', c['linha']):
            raise KeyboardInterrupt('cancelado')
        self.d.nota('linha K')
        try:
            self.d.transact(c['pino'], ok_7f00=True)
        except IOError as e:        # motor ligado: o scanner responde 7F <pino> 07
            recusa = 'erro: 7f %02x' % c['pino'][0]
            if 'destravar' not in c or isinstance(e, TimeoutError) or recusa not in str(e):
                raise
            self.d.nota('pino recusado; destravar e repetir')
            self.d.transact(c['destravar'], 2.0)
            self.d.transact(c['pino'], ok_7f00=True)
        self.d.nota('configurar linha K')
        self.d.transact(c['linha'], ok_7f00=True)
        r = None
        for _ in range(c['inicio_tentativas']):    # ignora "sem resposta" e tenta de novo
            try:
                r = self._ecu('início de comunicação', c['inicio'])
                break               # repetir depois que o 1º respondeu dava erro (visto no carro)
            except (TimeoutError, IOError):
                time.sleep(0.15)
        if r is None and 'acordar' in c:           # central dormindo: init de 5 baud (leva ~2,5 s)
            self.d.nota('acordar central (init 5 baud)')
            r = self.d.transact(c['acordar'], c['acordar_timeout_s'])
            self.ultimo = time.monotonic()
        if r is None:
            raise TimeoutError('a central não respondeu ao início de comunicação')
        return v, r

    def manter(self):
        """Mensagem de manutenção, se ficou `manter_apos_s` sem falar."""
        c = self.perfil.conexao
        if time.monotonic() - self.ultimo > c['manter_apos_s']:
            self._ecu('manter conexão', c['manter'])

    def ler_falhas(self):
        """-> (lista, dados). lista = None se a central deu "sem resposta válida"; senão dicts
        {codigo, tipo, presente, bruto, nome, tipo_nome, pendente}."""
        f = self.perfil.leitura['falhas']
        d = self._ecu('ler códigos de falha', hex_pedido(f['pedido']))
        sem = hex_pedido(f['sem_resposta']) if 'sem_resposta' in f else b'\x32'
        lst = decodificar_falhas(d, sem)
        if lst is None:
            return None, d
        for x in lst:
            x['nome'] = self.perfil.nome_falha(x['codigo'])
            x['tipo_nome'] = self.perfil.nome_tipo(x['tipo'])
            x['pendente'] = self.perfil.falha_pendente(x['codigo'])
        return lst, d

    def ler_parametros(self, blocos=None):
        """blocos: None = todos; ou lista de ids de bloco (ex. [0] = só o da lambda, mais rápido).
        -> lista de (métrica, bruto, número, texto) ordenada pela `ordem` da métrica."""
        out = []
        for bid, pedido in self.perfil.blocos:
            if blocos is not None and bid not in blocos:
                continue
            d = self._ecu('ler bloco %d' % bid, pedido)
            for m in self.perfil.metricas:
                if m['bloco'] != bid:
                    continue
                try:
                    raw, num, txt = _perfil.converter(self.perfil, m, d)
                except IndexError:
                    raw, num, txt = None, None, '(resposta curta)'
                out.append((m, raw, num, txt))
        return sorted(out, key=lambda x: x[0]['ordem'])

    def escrever(self, id_acao, liberar_perigo=False):
        """Manda um comando de `perfil.escrita` (apagar falhas ou atuador). Devolve os dados da resposta.
        PermissionError se a escrita não foi habilitada ou se o comando é perigoso e não foi liberado."""
        e = self.perfil.escrita_por_id(id_acao)
        if not self.escrita:
            raise PermissionError('escrita desabilitada: %s não foi enviado (habilite os comandos de escrita)' % e['nome'])
        if e.get('perigoso') and liberar_perigo is not True:
            raise PermissionError('%s bloqueado no modo de segurança (precisa de confirmação extra)' % e['nome'])
        for a in e.get('antes', []):
            self._ecu('pré-comando %s' % a, _perfil.hex_bytes(a, 'antes'))
        rot = 'APAGAR' if e['tipo'] == 'apagar_falhas' else 'ACIONAR'
        return self._ecu('%s %s (%s)' % (rot, e['nome'], e['pedido']), _perfil.hex_bytes(e['pedido'], 'pedido'))
