"""Sessões de gravação: CSV no formato de sempre (registros/*.csv) + metadados.

sessoes/indice.json = {"sessoes": {id: {nome, inicio, fim, duracao, csv, captura,
notas, eventos: [{s, hora, texto}], quedas: [[s_ini, s_fim]], leituras, reconexoes}}}
O id é o nome do CSV sem extensão. CSVs sem metadados (gravações antigas) também
aparecem na lista, com o que dá para tirar do próprio arquivo.
"""
import csv, io, json, os, threading, time

import faixas, perfil as _perfil, registro

RAIZ = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
PASTA = os.path.join(RAIZ, 'sessoes')
REGISTROS = os.path.join(RAIZ, 'registros')
QUEDA_S = 2.0          # sem leitura por mais que isso = queda de conexão


class Indice:
    def __init__(self, pasta=PASTA, registros=REGISTROS):
        self.pasta, self.registros = pasta, registros
        self.caminho = os.path.join(pasta, 'indice.json')
        self.trava = threading.RLock()
        try:
            with open(self.caminho, encoding='utf-8') as f:
                self.dados = json.load(f)
        except (OSError, ValueError):
            self.dados = {'sessoes': {}}

    def salvar(self):
        with self.trava:
            os.makedirs(self.pasta, exist_ok=True)
            tmp = self.caminho + '.tmp'
            with open(tmp, 'w', encoding='utf-8') as f:
                json.dump(self.dados, f, ensure_ascii=False, indent=1)
            os.replace(tmp, self.caminho)

    def csv(self, sid):
        m = self.dados['sessoes'].get(sid)
        nome = m['csv'] if m else sid + '.csv'
        if os.sep in nome or nome.startswith('.'):
            raise ValueError('sessão inválida')
        c = os.path.join(self.registros, nome)
        if not os.path.isfile(c):
            raise KeyError(sid)
        return c

    def get(self, sid):
        with self.trava:
            m = self.dados['sessoes'].get(sid)
            if m:
                return dict(m)
        return self._antiga(sid)

    def _antiga(self, sid):
        c = self.csv(sid)
        dur, leit = 0.0, 0
        with open(c, encoding='utf-8') as f:
            for row in csv.reader(f):
                if len(row) > 1 and row[0] != 'hora':
                    try:
                        dur, leit = float(row[1]), leit + 1
                    except ValueError:
                        pass
        ini = time.strftime('%Y-%m-%d %H:%M:%S', time.strptime(sid[:15], '%Y%m%d-%H%M%S')) if len(sid) >= 15 else ''
        return {'id': sid, 'nome': '', 'inicio': ini, 'fim': None, 'duracao': round(dur, 1), 'csv': sid + '.csv',
                'captura': None, 'notas': '', 'eventos': [], 'quedas': [], 'leituras': leit, 'reconexoes': None,
                'antiga': True}

    def listar(self):
        with self.trava:
            out = {k: dict(v) for k, v in self.dados['sessoes'].items()}
        try:
            nomes = os.listdir(self.registros)
        except OSError:
            nomes = []
        for n in nomes:
            sid = n[:-4]
            if n.endswith('.csv') and sid not in out and os.path.getsize(os.path.join(self.registros, n)) > 0:
                try:
                    out[sid] = self._antiga(sid)
                except (KeyError, ValueError, OSError):
                    pass
        for m in out.values():
            m['n_eventos'] = len(m.get('eventos') or [])
            m['n_quedas'] = len(m.get('quedas') or [])
        return sorted(out.values(), key=lambda m: m.get('inicio') or '', reverse=True)

    def atualizar(self, sid, **campos):
        with self.trava:
            if sid not in self.dados['sessoes']:      # gravação antiga: cria os metadados na 1ª edição
                m = self._antiga(sid)
                m.pop('antiga', None)
                self.dados['sessoes'][sid] = m
            m = self.dados['sessoes'][sid]
            for k in ('nome', 'notas'):
                if k in campos and campos[k] is not None:
                    m[k] = str(campos[k])[:2000]
            self.salvar()
            return dict(m)

    def novo_id(self):
        base = time.strftime('%Y%m%d-%H%M%S')
        sid, n = base, 2
        while sid in self.dados['sessoes'] or os.path.exists(os.path.join(self.registros, sid + '.csv')):
            sid, n = '%s-%d' % (base, n), n + 1
        return sid


class Gravacao:
    """Sessão sendo gravada. linha() é chamada a cada leitura (thread do monitor);
    evento() pela web. prefixo = leituras do buffer [(t, hora, vals)] já feitas."""
    SALVAR_A_CADA = 10.0

    def __init__(self, indice, nome='', captura=None, prefixo=(), reconexoes=0, agora=None):
        self.ind = indice
        self.sid = indice.novo_id()
        os.makedirs(indice.registros, exist_ok=True)
        agora = time.monotonic() if agora is None else agora
        self.t0 = prefixo[0][0] if prefixo else agora
        self.csv = registro.RegistroCSV(os.path.join(indice.registros, self.sid + '.csv'), t0=self.t0)
        self.rec0 = reconexoes
        self.ultimo_t = None
        self.salvo = 0.0
        self.trava = threading.Lock()
        self.meta = {'id': self.sid, 'nome': nome or '', 'inicio': time.strftime('%Y-%m-%d %H:%M:%S',
                     time.localtime(time.time() - (agora - self.t0))), 'fim': None, 'duracao': 0.0,
                     'csv': self.sid + '.csv', 'captura': os.path.basename(captura) if captura else None,
                     'notas': '', 'eventos': [], 'quedas': [], 'leituras': 0, 'reconexoes': 0}
        with indice.trava:
            indice.dados['sessoes'][self.sid] = self.meta
            indice.salvar()
        for t, hora, vals in prefixo:
            self.linha(vals, t, hora)

    def s(self, t=None):
        return (time.monotonic() if t is None else t) - self.t0

    def linha(self, vals, t=None, hora=None, reconexoes=None):
        t = time.monotonic() if t is None else t
        with self.trava:
            self.csv.linha(vals, t, hora)
            if self.ultimo_t is not None and t - self.ultimo_t > QUEDA_S:
                self.meta['quedas'].append([round(self.s(self.ultimo_t), 2), round(self.s(t), 2)])
            self.ultimo_t = t
            self.meta['leituras'] += 1
            self.meta['duracao'] = round(self.s(t), 1)
            if reconexoes is not None:
                self.meta['reconexoes'] = reconexoes - self.rec0
            if t - self.salvo > self.SALVAR_A_CADA:
                self.salvo = t
                self.ind.salvar()

    def evento(self, texto, t=None):
        ev = {'s': round(self.s(t), 2), 'hora': time.strftime('%H:%M:%S'), 'texto': (texto or 'evento').strip()[:200]}
        with self.trava:
            self.meta['eventos'].append(ev)
            self.ind.salvar()
        return ev

    def falhas(self, motivo, lst, t=None):
        """Leitura de falhas durante a gravação: guarda e vira evento o que for novo."""
        nome = lambda f: '%s %s%s (%s)' % (f['hex'], f['nome'], ' [nome provável]' if f.get('pendente') else '',
                                           'presente' if f['presente'] else 'memorizada')
        atual = {(f['codigo'], f['tipo']): f['presente'] for f in lst}
        with self.trava:
            ant = getattr(self, '_falhas', None)
            self.meta.setdefault('falhas', []).append({'s': round(self.s(t), 2), 'motivo': motivo,
                'falhas': [{k: f[k] for k in ('codigo', 'tipo', 'presente', 'hex', 'nome', 'tipo_nome', 'pendente')} for f in lst]})
            self._falhas = atual
        if ant is None:
            texto = 'falhas (%s): %s' % (motivo, ', '.join(map(nome, lst)) if lst else 'nenhuma')
        else:
            novas = [f for f in lst if ant.get((f['codigo'], f['tipo'])) != f['presente']]
            if not novas and motivo != 'luz acendeu':
                if motivo == 'fim':
                    self.ind.salvar()
                return None
            texto = 'falha (%s): %s' % (motivo, ', '.join(map(nome, novas)) if novas else
                                        'nenhum código novo; na central: ' + (', '.join(map(nome, lst)) or 'nenhum'))
        return self.evento(texto, t)

    def fechar(self):
        with self.trava:
            self.meta['fim'] = time.strftime('%Y-%m-%d %H:%M:%S')
            self.csv.close()
            self.ind.salvar()
        return dict(self.meta)


def carregar(indice, sid, perfil, fx=None):
    """Dados de uma sessão para gráficos/reprodução: colunas numéricas, estados e lambda."""
    fx = fx or faixas.Faixas(perfil)
    meta = indice.get(sid)
    linhas = registro.ler_csv(indice.csv(sid), perfil)
    ps = {m['ordem']: m for m in perfil.metricas}
    ts = [round(s, 2) for s, _, _ in linhas]
    series = {o: [] for o in ps}
    for _, _, raws in linhas:
        for o, p in ps.items():
            v = _perfil.numero(perfil, p, raws.get(o))
            series[o].append(None if v is None else round(v, 3))
    ctxs = [faixas.contexto(perfil, {o: series[o][k] for o in ps}) for k in range(len(ts))]
    olam = perfil.lambda_['ordem'] if perfil.lambda_ else None
    quente = faixas.COND_LAMBDA in perfil.condicoes
    lam = registro.analisar_serie(ts, series[olam] if olam else [None] * len(ts),
                                  [(faixas.COND_LAMBDA in c) or not quente for c in ctxs])
    cod = {'neutro': 0, 'normal': 1, 'atencao': 2, 'fora': 3}
    estados = {o: [cod[fx.avaliar(o, series[o][k], ctxs[k], lam['diag'][k])['estado']] for k in range(len(ts))]
               for o in ps}
    return {'meta': meta, 't': ts, 'hora': [h for _, h, _ in linhas], 'series': series, 'estados': estados,
            'lambda': {'diag': lam['diag'], 'resumo': lam['resumo'], 'tempo': lam['tempo']}}


def exportar_csv(indice, sid):
    """O CSV original com uma coluna 'evento' no fim (texto do evento na linha mais próxima)."""
    meta = indice.get(sid)
    evs = sorted(meta.get('eventos') or [], key=lambda e: e['s'])
    out = io.StringIO()
    w = csv.writer(out)
    with open(indice.csv(sid), encoding='utf-8') as f:
        rows = list(csv.reader(f))
    ss = []
    for r in rows:
        try:
            ss.append(float(r[1]) if r and r[0] != 'hora' else None)
        except (ValueError, IndexError):
            ss.append(None)
    marcas = {}
    for e in evs:
        cand = [(abs(s - e['s']), k) for k, s in enumerate(ss) if s is not None]
        if cand:
            k = min(cand)[1]
            marcas[k] = (marcas[k] + ' | ' if k in marcas else '') + e['texto']
    for k, r in enumerate(rows):
        w.writerow(r + (['evento'] if r and r[0] == 'hora' else [marcas.get(k, '')]))
    return out.getvalue()
