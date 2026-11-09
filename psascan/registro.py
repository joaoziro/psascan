"""Registro em CSV dos parâmetros ao vivo e análise da sonda lambda."""
import collections, csv, os, time


def caminho_registro(pasta=None):
    pasta = pasta or os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'registros')
    os.makedirs(pasta, exist_ok=True)
    return os.path.join(pasta, time.strftime('%Y%m%d-%H%M%S') + '.csv')


def eh_lambda(m):
    """A métrica do perfil que a análise de trocas rico↔pobre avalia."""
    return m.get('analise') == 'lambda'


class RegistroCSV:
    """Uma linha por leitura: hora, segundos, e para cada métrica o texto (com a unidade do perfil)
    e o bruto, com o `nome` da métrica como coluna. Abre direto no LibreOffice."""

    def __init__(self, caminho, t0=None):
        """t0: instante (time.monotonic) do s = 0; o padrão é agora."""
        self.caminho = caminho
        self.f = open(caminho, 'a', newline='', encoding='utf-8')
        self.w = csv.writer(self.f)
        self.colunas = None
        self.t0 = time.monotonic() if t0 is None else t0

    def linha(self, vals, t=None, hora=None):
        """t (monotonic) e hora (epoch) permitem gravar leituras já feitas (buffer)."""
        nomes = [m['nome'] for m, _, _, _ in vals]
        if nomes != self.colunas:            # cabeçalho novo se mudar o conjunto (ex. troca de modo)
            self.colunas = nomes
            self.w.writerow(['hora', 's'] + [x for n in nomes for x in (n, n + ' (bruto)')])
        t = time.monotonic() if t is None else t
        self.w.writerow([time.strftime('%H:%M:%S', time.localtime(hora)), '%.2f' % (t - self.t0)] +
                        [x for _, raw, _, txt in vals for x in (txt, raw)])
        self.f.flush()

    def close(self):
        self.f.close()


def ler_csv(caminho, perfil):
    """Gravação do psascan -> [(s, hora, {ordem: bruto})] a partir das colunas '<nome> (bruto)',
    com o nome da métrica no perfil. Aceita cabeçalhos repetidos no meio (troca de modo rápido/completo)."""
    por_nome = {m['nome']: m['ordem'] for m in perfil.metricas}
    linhas, cab, idx = [], None, []
    with open(caminho, encoding='utf-8') as f:
        for row in csv.reader(f):
            if row and row[0] == 'hora':
                cab = row
                idx = [(k, por_nome.get(n[:-8].strip())) for k, n in enumerate(row) if n.endswith(' (bruto)')]
                continue
            if not cab or len(row) != len(cab):
                continue
            try:
                s = float(row[1])
            except ValueError:
                continue
            raws = {o: int(row[k]) for k, o in idx if o and row[k] not in ('', 'None')}
            linhas.append((s, row[0], raws))
    return linhas


class AnaliseLambda:
    """Sonda de banda estreita: ~100 mV = pobre, ~900 mV = rico, chaveando em torno
    de 450 mV. Conta as trocas rico↔pobre com histerese (350/550 mV)."""
    BAIXO, ALTO = 350, 550

    def __init__(self, guardar=60.0):
        self.amostras = collections.deque()
        self.guardar = guardar

    def add(self, t, mv):
        self.amostras.append((t, mv))
        while self.amostras and t - self.amostras[0][0] > self.guardar:
            self.amostras.popleft()

    def _janela(self, seg):
        a = list(self.amostras)
        if not a:
            return []
        fim = a[-1][0]
        return [(t, v) for t, v in a if fim - t <= seg]

    def resumo(self, seg=10.0):
        a = self._janela(seg)
        if len(a) < 5:
            return {'diagnostico': 'aguardando dados', 'amostras': len(a)}
        vs = [v for _, v in a]
        dur = max(a[-1][0] - a[0][0], 1e-6)
        trocas, lado = 0, None
        for v in vs:
            novo = 'rico' if v >= self.ALTO else 'pobre' if v <= self.BAIXO else lado
            if lado and novo != lado:
                trocas += 1
            lado = novo
        mn, mx, media = min(vs), max(vs), sum(vs) / len(vs)
        rico = 100.0 * sum(v >= 450 for v in vs) / len(vs)
        hz = trocas / 2 / dur
        if mx - mn < 150:
            if media < 300:
                diag = 'PARADA EM POBRE (~%d mV)' % media
            elif media > 600:
                diag = 'PARADA EM RICO (~%d mV)' % media
            else:
                diag = 'PARADA NO MEIO (~%d mV)' % media
        elif trocas < 2:
            diag = 'VARIA, MAS DEVAGAR (%d troca(s) em %.0f s)' % (trocas, dur)
        else:
            diag = 'OSCILANDO (~%.1f Hz)' % hz
        return {'diagnostico': diag, 'amostras': len(a), 'segundos': round(dur, 1),
                'min': round(mn), 'max': round(mx), 'media': round(media),
                'trocas': trocas, 'hz': round(hz, 2), 'pct_rico': round(rico)}


def analisar_serie(ts, mvs, validos=None, janela=10.0):
    """Lambda de uma gravação inteira. ts/mvs: listas (mv pode ser None);
    validos: lista de bool (ex. motor quente) para o resumo geral.
    -> {'diag': [diagnóstico em cada instante], 'resumo': {...}, 'tempo': {veredito: %}}"""
    a = AnaliseLambda(guardar=janela + 1)
    diag, tempo = [], {}
    sel_t, sel_v = [], []
    for k, (t, v) in enumerate(zip(ts, mvs)):
        if v is None:
            diag.append(None)
            continue
        a.add(t, v)
        d = a.resumo(janela)['diagnostico']
        diag.append(d)
        if validos is None or validos[k]:
            sel_t.append(t)
            sel_v.append(v)
            chave = d.split(' (')[0]
            tempo[chave] = tempo.get(chave, 0) + 1
    tot = sum(tempo.values()) or 1
    g = AnaliseLambda(guardar=1e12)
    for t, v in zip(sel_t, sel_v):
        g.amostras.append((t, v))
    resumo = g.resumo(seg=1e12) if sel_v else {'diagnostico': 'sem dados', 'amostras': 0}
    if sel_v:          # trocas/s na gravação inteira (ignora buracos de queda: dur = soma dos passos < 2 s)
        dur = sum(min(b - a_, 2.0) for a_, b in zip(sel_t, sel_t[1:])) or 1e-6
        resumo['trocas_s'] = round(resumo['trocas'] / dur, 2)
    return {'diag': diag, 'resumo': resumo, 'tempo': {k: round(100.0 * n / tot) for k, n in tempo.items()}}


DICAS_LAMBDA = """Como interpretar (sonda de 1 fio/aquecida, banda estreita):
- Só vale com o motor QUENTE (água > ~80 °C); frio a central ignora a sonda (malha aberta).
- Normal: oscila entre ~100 e ~900 mV, várias trocas a cada 10 s; mais rápido a ~2000-2500 rpm.
- Parada em pobre: entrada de ar falso, pressão de combustível baixa, injetor entupido, sonda morta.
- Parada em rico: pressão alta/regulador, injetor pingando, sonda contaminada, canister.
- Parada no meio (~450 mV): sonda sem aquecimento ou morta, fio da sonda interrompido.
- Varia devagar: sonda envelhecida/preguiçosa.
Compare também MAP, temperatura da água e do ar no CSV."""
