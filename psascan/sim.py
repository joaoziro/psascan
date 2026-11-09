"""Simulador do scanner PSA COM + uma central psa5 (a do perfil) num pseudo-terminal.

Uso: python3 sim.py            -> imprime o caminho do pty (ex.: /dev/pts/7)
     python3 cli.py --porta /dev/pts/7 falhas

Cenários (--cenario):
  fixo     valores constantes inventados (o formato segue o que a central manda)
  ralenti  marcha lenta quente com ruído (valores parecidos com os do carro)
  estrada  ciclo de 2 min: aquece, marcha lenta, acelera, cruzeiro, freio-motor
  --csv registros/x.csv  reproduz as colunas "(bruto)" de uma gravação real
Em todos, os bytes dos blocos são montados a partir dos valores brutos com as posições do
perfil: o caminho real (quadros, conversões) é exercitado. Só responde ao que está no perfil
(blocos, falhas, início, manter, escrita); qualquer outro comando fica sem resposta.
"""
import math, os, random, threading, time, tty

import dongle, perfil as _perfil, psa5, registro

# Valores brutos do cenário "fixo" (por id da métrica do perfil): marcha lenta quente, inventados
# no formato que a central manda. O simulador monta os bytes a partir do perfil (posição, tipo, fator).
FIXO = {'carga': 510, 'rpm': 27, 'pedal': 16, 'map': 13360, 'pressao_atm': 33400, 'agua': 60, 'ar': 120,
        'bateria': 190, 'borboleta': 30, 'velocidade': 0, 'ac_termistor': 1, 'ac_autorizacao': 1,
        'injecao': 400, 'avanco': 125, 'bobina_14': 790, 'bobina_23': 795, 'motor_passo': 25,
        'rele_principal': 1, 'alim_central': 1, 'luz_injecao': 0}


def ler_csv(caminho, perfil):
    """[(s, {ordem: bruto})] de uma gravação (ver registro.ler_csv)."""
    return [(t, raws) for t, _, raws in registro.ler_csv(caminho, perfil)]


def lambda_mv(modo, t):
    """Tensão da sonda para o modo 'oscila' (~1 Hz entre 100 e 850 mV), 'pobre', 'rico' ou 'meio'."""
    return {'oscila': 475 + 375 * math.copysign(1, math.sin(2 * math.pi * t)) * min(1, abs(3 * math.sin(2 * math.pi * t))),
            'pobre': 110, 'rico': 850, 'meio': 450}[modo]


class Cenario:
    """Valores físicos plausíveis ao longo do tempo (por id da métrica) -> brutos."""

    def __init__(self, perfil, nome, lambda_='oscila'):
        self.perfil, self.nome, self.lambda_ = perfil, nome, lambda_
        self.rnd = random.Random(306)

    def valores(self, t):
        n = self.rnd.gauss
        agua = min(92, 40 + t * 0.35) if self.nome == 'estrada' else 92
        quente = agua >= 80
        v = {'rpm': 840, 'bateria': 14.0, 'avanco': 12, 'carga': 27, 'pedal': 16, 'borboleta': 16, 'map': 450,
             'pressao_atm': 887, 'agua': agua, 'ar': 26 + min(14, t / 20), 'bobina_14': 3.38, 'bobina_23': 3.37,
             'injecao': 1.55, 'motor_passo': 18 if quente else 80 - agua * 0.7, 'velocidade': 0,
             'rele_principal': 1, 'alim_central': 1, 'luz_injecao': 0, 'ac_termistor': 0, 'ac_autorizacao': 0}
        fase = 'ralenti'
        if self.nome == 'estrada':
            c = t % 120
            if 20 <= c < 35:           # acelerando em 1ª/2ª/3ª
                fase, vel = 'acelera', (c - 20) * 5.3
                marcha = 1 + min(2, int((c - 20) / 5))
                v.update({'rpm': 1400 + ((c - 20) % 5) * 700 + marcha * 100, 'velocidade': vel, 'pedal': 0,
                          'borboleta': 45, 'map': 880, 'carga': 80, 'injecao': 6.0, 'avanco': 22})
            elif 35 <= c < 80:         # cruzeiro ~80 km/h em 5ª
                fase = 'cruzeiro'
                v.update({'rpm': 2600 + 60 * math.sin(c), 'velocidade': 80 + 2 * math.sin(c / 3), 'pedal': 0,
                          'borboleta': 24, 'map': 600, 'carga': 45, 'injecao': 3.4, 'avanco': 32})
            if c >= 100:               # luz de injeção acende no fim do ciclo (testa a leitura automática de falhas)
                v['luz_injecao'] = 1
            if 80 <= c < 95:           # freio-motor: corte de injeção
                fase, vel = 'corte', 80 - (c - 80) * 5.3
                v.update({'rpm': max(900, 2600 - (c - 80) * 120), 'velocidade': max(0, vel), 'map': 250, 'carga': 12,
                          'injecao': 0.0, 'avanco': 18})
        lam = {'pobre': 110, 'rico': 850, 'meio': 450}.get(self.lambda_)
        if lam is None:
            if not quente:
                lam = 450                                   # malha aberta: sonda ainda fria
            elif fase == 'corte':
                lam = 90
            elif fase == 'acelera':
                lam = 860                                   # enriquecimento em plena carga
            else:
                hz = 0.6 if fase == 'ralenti' else 1.4
                lam = 475 + 375 * math.copysign(1, math.sin(2 * math.pi * hz * t)) * \
                    min(1, abs(3 * math.sin(2 * math.pi * hz * t)))
        v['lambda'] = lam + n(0, 15)
        v['rpm'] += n(0, 12); v['bateria'] += n(0, 0.04); v['avanco'] += n(0, 1.5); v['map'] += n(0, 6)
        v['injecao'] += n(0, 0.03) if v['injecao'] else 0
        return v

    def raws(self, t):
        """{ordem: bruto} dos ids que existem no perfil."""
        p = self.perfil
        out = {}
        for id_, x in self.valores(t).items():
            try:
                m = p.metrica(id_)
            except KeyError:
                continue
            out[m['ordem']] = _perfil.inverso(p, m, x)
        return out


FALHAS_TESTE = ((0x22, 6, True), (0x1A1, 0, False))
# lidas no carro em 2026-10-25 (motor ligado): 127h/295 riqueza presente, 02A/42 lambda e 029/41 detonação memorizadas
FALHAS_DO_CARRO = ((0x127, 2, True), (0x2A, 1, False), (0x29, 1, False))


class Simulador:
    def __init__(self, falhas=None, lambda_='oscila', queda_a_cada=None,
                 cenario='fixo', csv=None, imitar_0f=False, perfil=None):
        """lambda_: 'oscila' (~1 Hz entre 100 e 850 mV), 'pobre', 'rico' ou 'meio'.
        queda_a_cada: segundos; simula o scanner sumindo por 3 s (testa a reconexão).
        cenario: 'fixo', 'ralenti' ou 'estrada'; csv: reproduz uma gravação real.
        imitar_0f: como a central real, só o 1º pedido de início responde; os seguintes dão 7F 04 13."""
        self.falhas = list(falhas if falhas is not None else FALHAS_DO_CARRO if csv else FALHAS_TESTE)
        self.lambda_ = lambda_
        self.queda_a_cada = queda_a_cada
        self.imitar_0f = imitar_0f
        self._ultimo_inicio = 0
        self.perfil = perfil or _perfil.carregar()
        self.cenario = Cenario(self.perfil, cenario, lambda_) if cenario != 'fixo' and not csv else None   # CSV tem prioridade
        self.gravacao = ler_csv(csv, self.perfil) if csv else None
        self.t0 = time.monotonic()
        self.log = []
        self._conhecidos = self._pedidos_conhecidos()
        self.mestre, escravo = os.openpty()
        tty.setraw(escravo)
        self.caminho = os.ttyname(escravo)
        self._escravo = escravo
        threading.Thread(target=self._loop, daemon=True).start()

    def _pedidos_conhecidos(self):
        """Pedidos à central que existem no perfil; o resto fica sem resposta."""
        p, c = self.perfil, self.perfil.conexao
        ok = {c['inicio'], c['manter'], _perfil.hex_bytes(p.leitura['falhas']['pedido'], 'pedido')}
        ok.update(ped for _, ped in p.blocos)
        for e in p.escrita:
            ok.add(_perfil.hex_bytes(e['pedido'], 'pedido'))
            ok.update(_perfil.hex_bytes(a, 'antes') for a in e.get('antes', []))
        return ok

    def ecu(self, d):
        """Dados da resposta da central a um pedido (None = não responde)."""
        d = bytes(d)
        p, c = self.perfil, self.perfil.conexao
        if d not in self._conhecidos:
            return None
        if d in (c['inicio'], c['manter']):
            return d
        if d == _perfil.hex_bytes(p.leitura['falhas']['pedido'], 'pedido'):
            out = b'\x73'
            for cod, tipo, presente in self.falhas:
                v = (cod << 3) | tipo | (0 if presente else 0x2000)
                out += v.to_bytes(2, 'big')
            return out
        for bid, ped in p.blocos:
            if d == ped:
                return _perfil.montar_bloco(p, bid, self.raws_agora(bid))
        for e in p.escrita:
            if d == _perfil.hex_bytes(e['pedido'], 'pedido'):
                if e['tipo'] == 'apagar_falhas':
                    self.falhas = []
                    return b'\xc3'          # visto na captura (docs/PROTOCOLO-SL96.md, 4.1): C2 C3 7A
                return b'\x66'              # visto na captura (4.2): B4 06 -> C2 66 D7
        return None                         # pré-comando: sem dados de resposta conhecidos

    def raws_agora(self, bloco=None):
        t = time.monotonic() - self.t0
        if self.cenario:
            return self.cenario.raws(t)
        if self.gravacao:
            g = self.gravacao
            dur = g[-1][0] - g[0][0] + 0.3
            alvo = g[0][0] + t % dur
            return min(g, key=lambda x: abs(x[0] - alvo))[1]
        raws = {}
        for id_, r in FIXO.items():
            try:
                raws[self.perfil.metrica(id_)['ordem']] = r
            except KeyError:
                pass
        if self.perfil.lambda_:
            raws[self.perfil.lambda_['ordem']] = _perfil.inverso(self.perfil, self.perfil.lambda_, lambda_mv(self.lambda_, t))
        return raws

    def caido(self):
        if not self.queda_a_cada:
            return False
        return (time.monotonic() - self.t0) % self.queda_a_cada > self.queda_a_cada - 3

    def resposta(self, p):
        """Payload de resposta do dongle ao payload p (None = sem resposta)."""
        cmd, c = p[0], self.perfil.conexao
        if cmd == dongle.CMD_VERSAO:
            return bytes([0x98, 6, 0, 1, 2])
        if 'acordar' in c and p == c['acordar']:
            return b'\x87' + b'SL964'          # init 5 baud: no carro vem 87 … 'SL964' …
        if 'destravar' in c and p == c['destravar']:
            return bytes([0xA8, 0, 0])
        if cmd in (c['linha'][0], c['pino'][0]):
            return bytes([0x7F, cmd, 0, 0, 0, 0])
        if cmd == dongle.CMD_ECU:
            d = psa5.desquadro(p[3:], base=0x80)
            if self.imitar_0f and d == c['inicio']:
                agora, antes = time.monotonic(), self._ultimo_inicio
                self._ultimo_inicio = agora
                if agora - antes < 1.0:
                    return bytes([0x7F, 0x04, 0x13])     # central não respondeu
            r = self.ecu(d)
            if r is None:
                return None
            q = bytes([0xC1 + len(r)]) + r
            return b'\x84' + q + bytes([(0xFF - sum(q)) & 0xFF])
        return None

    def _loop(self):
        buf = b''
        while True:
            try:
                buf += os.read(self.mestre, 4096)
            except OSError:
                return
            while True:
                p, buf = dongle.unframe(buf)
                if p is None:
                    break
                self.log.append(p)
                if self.caido():
                    continue          # finge que o Bluetooth caiu: não responde
                if p[0] == dongle.CMD_ECU:
                    time.sleep(0.03)  # ~ tempo de ida e volta na linha K a 10400 baud
                r = self.resposta(p)
                if r is not None:
                    os.write(self.mestre, dongle.frame(r, True))


if __name__ == '__main__':
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument('--lambda', dest='lam', default='oscila', choices=['oscila', 'pobre', 'rico', 'meio'])
    ap.add_argument('--queda', type=float, help='simula queda do Bluetooth a cada N segundos')
    ap.add_argument('--cenario', default='fixo', choices=['fixo', 'ralenti', 'estrada'])
    ap.add_argument('--csv', help='reproduz uma gravação (registros/*.csv)')
    o = ap.parse_args()
    s = Simulador(lambda_=o.lam, queda_a_cada=o.queda, cenario=o.cenario, csv=o.csv)
    print(s.caminho, flush=True)
    while True:
        time.sleep(3600)
