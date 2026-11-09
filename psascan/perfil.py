"""Perfil de central: carrega, valida e converte conforme docs/PERFIL.md.

Um perfil (perfis/<id>/perfil.json) é só dados: como conectar, o que ler, como converter os
bytes em valores, faixas de referência, códigos de falha e comandos de escrita. O código que
fala com a central é a família de protocolo (psa5.py), escolhida por `perfil.familia`.
"""
import json, os, re

RAIZ = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
PERFIL_PADRAO = os.path.join(RAIZ, 'perfis', 'sagem-sl96', 'perfil.json')

FORMATO = 1
FAMILIAS = ('psa5',)
SCANNERS = ('psa-com',)
NIVEIS = ('V1', 'V2', 'V3', 'pendente')
TIPOS_METRICA = ('u8', 'u16', 'bit', 'enum', 'curva')
ANALISES = ('lambda',)
DECODIFICACOES = ('psa5-pares',)
TIPOS_ESCRITA = ('apagar_falhas', 'atuador')
OPS = {'>': lambda a, b: a > b, '>=': lambda a, b: a >= b, '<': lambda a, b: a < b,
       '<=': lambda a, b: a <= b, '==': lambda a, b: a == b, '!=': lambda a, b: a != b}
ID_PERFIL = re.compile(r'^[a-z0-9-]+$')
ID_METRICA = re.compile(r'^[a-z0-9_]+$')


class PerfilInvalido(ValueError):
    """O perfil não segue o formato; a mensagem diz o que e onde."""


def _erro(onde, msg):
    raise PerfilInvalido('%s: %s' % (onde, msg))


def _num(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool)


def _int(x):
    return isinstance(x, int) and not isinstance(x, bool)


def _campo(d, k, tipo, onde, opcional=False):
    """Devolve d[k] conferindo o tipo ('texto', 'int', 'num', 'bool', 'lista', 'obj')."""
    if not isinstance(d, dict):
        _erro(onde, 'deveria ser um objeto')
    if k not in d:
        if opcional:
            return None
        _erro(onde, 'falta o campo "%s"' % k)
    v = d[k]
    ok = {'texto': isinstance(v, str), 'int': _int(v), 'num': _num(v), 'bool': isinstance(v, bool),
          'lista': isinstance(v, list), 'obj': isinstance(v, dict)}[tipo]
    if not ok:
        _erro('%s.%s' % (onde, k) if onde else k, 'tipo inválido (esperado %s)' % tipo)
    return v


def hex_bytes(v, onde):
    """'13 07 03 00' -> bytes. Só hexadecimal de 2 dígitos separado por espaço."""
    if not isinstance(v, str) or not re.fullmatch(r'[0-9A-Fa-f]{2}( [0-9A-Fa-f]{2})*', v.strip()):
        _erro(onde, 'bytes inválidos %r (use hexadecimal separado por espaço, ex. "13 07")' % (v,))
    return bytes.fromhex(v)


def _par(v, onde):
    if not (isinstance(v, list) and len(v) == 2 and all(_num(x) for x in v) and v[0] <= v[1]):
        _erro(onde, 'deveria ser [mín, máx] numérico, com mín <= máx')


class Perfil:
    def __init__(self, dados, caminho=None):
        self.caminho = caminho
        self.dados = dados
        self._validar(dados)
        self.id = dados['id']
        self.nome = dados['nome']
        self.familia = dados['familia']
        self.scanner = dados['scanner']
        self.compatibilidade = dados['compatibilidade']
        self.documentacao = dados.get('documentacao')
        self.conexao = self._conexao
        self.blocos = [(b['id'], hex_bytes(b['pedido'], 'leitura.blocos')) for b in dados['leitura']['blocos']]
        self.leitura = dados['leitura']
        self.metricas = self._metricas
        self._por_ordem = {m['ordem']: m for m in self.metricas}
        self._por_id = {m['id']: m for m in self.metricas}
        self.curvas = dados.get('curvas', {})
        self.condicoes = dados['condicoes']
        self.falhas = dados['falhas']
        self.escrita = dados.get('escrita', [])
        self.lambda_ = next((m for m in self.metricas if m.get('analise') == 'lambda'), None)
        self.luz_falha = next((m for m in self.metricas if m.get('luz_falha')), None)

    # ---- consulta ----
    def metrica(self, ordem_ou_id):
        """Por ordem (int) ou id (texto). KeyError se não existir."""
        tab = self._por_ordem if isinstance(ordem_ou_id, int) else self._por_id
        return tab[ordem_ou_id]

    def escrita_por_id(self, id_):
        for e in self.escrita:
            if e['id'] == id_:
                return e
        raise KeyError(id_)

    def atuadores(self):
        return [e for e in self.escrita if e['tipo'] == 'atuador']

    def escrita_apagar(self):
        return next((e for e in self.escrita if e['tipo'] == 'apagar_falhas'), None)

    def info_falha(self, codigo):
        """{'nome', 'nivel', 'causas', 'pendente', 'conhecido'} do código (decimal)."""
        f = self.falhas['codigos'].get(str(codigo))
        if f is None:
            return {'nome': 'código 0x%X (não identificado)' % codigo, 'nivel': None, 'causas': [],
                    'pendente': False, 'conhecido': False}
        return {'nome': f['nome'], 'nivel': f.get('nivel'), 'causas': list(f.get('causas', [])),
                'pendente': f.get('nivel') == 'pendente', 'conhecido': True}

    def nome_falha(self, codigo):
        return self.info_falha(codigo)['nome']

    def falha_pendente(self, codigo):
        """True se o nome é só provável (ainda não visto em captura)."""
        return self.info_falha(codigo)['pendente']

    def nome_tipo(self, tipo):
        t = self.falhas['tipos'].get(str(tipo))
        return t['nome'] if t else 'tipo %d' % tipo

    def tipo_pendente(self, tipo):
        t = self.falhas['tipos'].get(str(tipo))
        return bool(t) and t.get('nivel') == 'pendente'

    def pendente(self, m):
        """A métrica (ou a curva dela) ainda não foi confirmada: a tela diz "não confirmado"."""
        if m.get('nivel') == 'pendente':
            return True
        c = self.curvas.get(m.get('curva')) if m.get('tipo') == 'curva' else None
        return bool(c) and c.get('nivel') == 'pendente'

    def decimais(self, m):
        return m.get('decimais', 0) if m['tipo'] in ('u8', 'u16', 'curva') else 0

    # ---- validação ----
    def _validar(self, d):
        if not isinstance(d, dict):
            _erro('perfil', 'deveria ser um objeto JSON')
        if _campo(d, 'formato', 'int', '') != FORMATO:
            _erro('formato', 'versão %r não suportada (esta ferramenta lê o formato %d)' % (d['formato'], FORMATO))
        if not ID_PERFIL.fullmatch(_campo(d, 'id', 'texto', '')):
            _erro('id', 'use só a-z, 0-9 e hífen')
        _campo(d, 'nome', 'texto', '')
        comp = _campo(d, 'compatibilidade', 'lista', '')
        if not all(isinstance(x, str) for x in comp):
            _erro('compatibilidade', 'deveria ser uma lista de textos')
        if _campo(d, 'scanner', 'texto', '') not in SCANNERS:
            _erro('scanner', 'scanner %r não suportado (hoje: %s)' % (d['scanner'], ', '.join(SCANNERS)))
        if _campo(d, 'familia', 'texto', '') not in FAMILIAS:
            _erro('familia', 'família %r não suportada (hoje: %s)' % (d['familia'], ', '.join(FAMILIAS)))
        _campo(d, 'documentacao', 'texto', '', opcional=True)
        self._conexao = self._validar_conexao(_campo(d, 'conexao', 'obj', ''))
        bloco_ids = self._validar_leitura(_campo(d, 'leitura', 'obj', ''))
        curvas = self._validar_curvas(_campo(d, 'curvas', 'obj', '', opcional=True) or {})
        metricas = self._validar_metricas(_campo(d, 'metricas', 'lista', ''), bloco_ids, curvas)
        self._metricas = metricas
        self._validar_condicoes(_campo(d, 'condicoes', 'obj', ''), {m['id'] for m in metricas})
        self._validar_faixas(metricas, d['condicoes'])
        self._validar_falhas(_campo(d, 'falhas', 'obj', ''))
        self._validar_escrita(_campo(d, 'escrita', 'lista', '', opcional=True) or [])

    def _validar_conexao(self, c):
        # família psa5: os comandos do scanner são bytes; o resto são números e texto
        out = dict(c)
        for k in ('pino', 'linha', 'inicio', 'manter'):
            _campo(c, k, 'texto', 'conexao')
            out[k] = hex_bytes(c[k], 'conexao.' + k)
        for k in ('destravar', 'acordar'):
            if k in c:
                _campo(c, k, 'texto', 'conexao')
                out[k] = hex_bytes(c[k], 'conexao.' + k)
        if _campo(c, 'inicio_tentativas', 'int', 'conexao') < 1:
            _erro('conexao.inicio_tentativas', 'deveria ser >= 1')
        for k in ('manter_apos_s',) + (('acordar_timeout_s',) if 'acordar' in c else ()):
            if _campo(c, k, 'num', 'conexao') <= 0:
                _erro('conexao.' + k, 'deveria ser > 0')
        _campo(c, 'descricao', 'texto', 'conexao', opcional=True)
        return out

    def _validar_leitura(self, le):
        blocos = _campo(le, 'blocos', 'lista', 'leitura')
        if not blocos:
            _erro('leitura.blocos', 'precisa de pelo menos um bloco')
        ids = set()
        for k, b in enumerate(blocos):
            onde = 'leitura.blocos[%d]' % k
            i = _campo(b, 'id', 'int', onde)
            if i in ids:
                _erro(onde, 'id %d repetido' % i)
            ids.add(i)
            hex_bytes(_campo(b, 'pedido', 'texto', onde), onde + '.pedido')
        f = _campo(le, 'falhas', 'obj', 'leitura')
        hex_bytes(_campo(f, 'pedido', 'texto', 'leitura.falhas'), 'leitura.falhas.pedido')
        if _campo(f, 'decodificacao', 'texto', 'leitura.falhas') not in DECODIFICACOES:
            _erro('leitura.falhas.decodificacao', 'algoritmo %r desconhecido' % f['decodificacao'])
        if _campo(f, 'sem_resposta', 'texto', 'leitura.falhas', opcional=True) is not None:
            if len(hex_bytes(f['sem_resposta'], 'leitura.falhas.sem_resposta')) != 1:
                _erro('leitura.falhas.sem_resposta', 'deveria ser um byte')
        return ids

    def _validar_curvas(self, curvas):
        for nome, c in curvas.items():
            onde = 'curvas.' + nome
            if _campo(c, 'tipo', 'texto', onde) != 'polinomio':
                _erro(onde + '.tipo', 'só existe "polinomio"')
            coef = _campo(c, 'coef', 'lista', onde)
            if not coef or not all(_num(x) for x in coef):
                _erro(onde + '.coef', 'deveria ser uma lista de números')
            if _campo(c, 'min', 'num', onde) > _campo(c, 'max', 'num', onde):
                _erro(onde, 'min maior que max')
            if 'nivel' in c and c['nivel'] not in NIVEIS:
                _erro(onde + '.nivel', 'nível %r inválido' % c['nivel'])
        return curvas

    def _validar_metricas(self, lst, bloco_ids, curvas):
        if not lst:
            _erro('metricas', 'precisa de pelo menos uma métrica')
        ords, ids, out = set(), set(), []
        for k, m0 in enumerate(lst):
            onde = 'metricas[%d]' % k
            if isinstance(m0, dict) and isinstance(m0.get('id'), str):
                onde = 'metrica "%s"' % m0['id']
            m = dict(m0) if isinstance(m0, dict) else _erro(onde, 'deveria ser um objeto')
            if not ID_METRICA.fullmatch(_campo(m, 'id', 'texto', onde)):
                _erro(onde + '.id', 'use só a-z, 0-9 e _')
            if m['id'] in ids:
                _erro(onde, 'id repetido')
            ids.add(m['id'])
            o = _campo(m, 'ordem', 'int', onde)
            if o in ords:
                _erro(onde, 'ordem %d repetida' % o)
            ords.add(o)
            for t in ('curto', 'nome', 'unidade'):
                _campo(m, t, 'texto', onde)
            if _campo(m, 'bloco', 'int', onde) not in bloco_ids:
                _erro(onde, 'bloco %d não existe em leitura.blocos' % m['bloco'])
            if _campo(m, 'byte', 'int', onde) < 0:
                _erro(onde + '.byte', 'deveria ser >= 0')
            tipo = _campo(m, 'tipo', 'texto', onde)
            if tipo not in TIPOS_METRICA:
                _erro(onde + '.tipo', 'tipo %r desconhecido (use %s)' % (tipo, ', '.join(TIPOS_METRICA)))
            if tipo in ('u8', 'u16'):
                _campo(m, 'fator', 'num', onde)
                m.setdefault('offset', 0)
                _campo(m, 'offset', 'num', onde)
            if tipo in ('u8', 'u16', 'curva'):
                m.setdefault('decimais', 0)
                if _campo(m, 'decimais', 'int', onde) < 0:
                    _erro(onde + '.decimais', 'deveria ser >= 0')
            if tipo == 'curva':
                if _campo(m, 'curva', 'texto', onde) not in curvas:
                    _erro(onde, 'curva "%s" não existe em curvas' % m['curva'])
            if tipo == 'bit':
                if not 0 <= _campo(m, 'bit', 'int', onde) <= 7:
                    _erro(onde + '.bit', 'deveria ser de 0 a 7')
            if tipo in ('bit', 'enum'):
                v = _campo(m, 'valores', 'obj', onde)
                if not all(isinstance(t, str) for t in v.values()) or not all(re.fullmatch(r'\d+', c) for c in v):
                    _erro(onde + '.valores', 'deveria mapear número (em texto) para texto')
                pend = _campo(m, 'valores_pendentes', 'lista', onde, opcional=True) or []
                for c in pend:
                    if c not in v:
                        _erro(onde + '.valores_pendentes', 'valor "%s" não está em valores' % c)
            if 'escala' in m:
                _par(m['escala'], onde + '.escala')
            if 'analise' in m and m['analise'] not in ANALISES:
                _erro(onde + '.analise', 'análise %r desconhecida' % m['analise'])
            if 'nivel' in m and m['nivel'] not in NIVEIS:
                _erro(onde + '.nivel', 'nível %r inválido (use %s)' % (m['nivel'], ', '.join(NIVEIS)))
            for t in ('obs', 'evidencia'):
                _campo(m, t, 'texto', onde, opcional=True)
            if 'luz_falha' in m:
                _campo(m, 'luz_falha', 'bool', onde)
            _campo(m, 'faixas', 'lista', onde, opcional=True)
            out.append(m)
        return sorted(out, key=lambda m: m['ordem'])

    def _validar_condicoes(self, conds, metricas):
        for nome, c in conds.items():
            onde = 'condicoes.' + nome
            _campo(c, 'texto', 'texto', onde)
            regras = _campo(c, 'regras', 'lista', onde)
            for k, r in enumerate(regras):
                o = '%s.regras[%d]' % (onde, k)
                if not isinstance(r, dict):
                    _erro(o, 'deveria ser um objeto')
                if 'condicao' in r:
                    if r['condicao'] not in conds and r['condicao'] != 'sempre':
                        _erro(o, 'condição "%s" não existe' % r['condicao'])
                else:
                    if _campo(r, 'metrica', 'texto', o) not in metricas:
                        _erro(o, 'métrica "%s" não existe' % r['metrica'])
                    if r.get('op') not in OPS:
                        _erro(o, 'op %r inválido (use %s)' % (r.get('op'), ' '.join(OPS)))
                    _campo(r, 'valor', 'num', o)
        # ciclos: segue as regras "condicao" a partir de cada condição
        estado = {}
        def visita(n, caminho):
            if estado.get(n) == 2 or n not in conds:
                return
            if estado.get(n) == 1:
                _erro('condicoes.' + n, 'ciclo entre condições (%s)' % ' → '.join(caminho + [n]))
            estado[n] = 1
            for r in conds[n]['regras']:
                if 'condicao' in r:
                    visita(r['condicao'], caminho + [n])
            estado[n] = 2
        for n in conds:
            visita(n, [])

    def _validar_faixas(self, metricas, conds):
        for m in metricas:
            for k, r in enumerate(m.get('faixas', [])):
                onde = 'metrica "%s".faixas[%d]' % (m['id'], k)
                if not isinstance(r, dict):
                    _erro(onde, 'deveria ser um objeto')
                q = _campo(r, 'quando', 'texto', onde)
                if q not in conds and q != 'sempre':
                    _erro(onde, 'condição "%s" não existe' % q)
                if 'normal_valor' in r:
                    if not _num(r['normal_valor']):
                        _erro(onde + '.normal_valor', 'deveria ser um número')
                elif 'normal' in r:
                    _par(r['normal'], onde + '.normal')
                    if 'atencao' in r:
                        _par(r['atencao'], onde + '.atencao')
                else:
                    _erro(onde, 'precisa de "normal" ou "normal_valor"')

    def _validar_falhas(self, f):
        tipos = _campo(f, 'tipos', 'obj', 'falhas')
        cods = _campo(f, 'codigos', 'obj', 'falhas')
        for sec, tab in (('tipos', tipos), ('codigos', cods)):
            for k, v in tab.items():
                onde = 'falhas.%s.%s' % (sec, k)
                if not re.fullmatch(r'\d+', k):
                    _erro(onde, 'a chave deveria ser um número decimal')
                _campo(v, 'nome', 'texto', onde)
                if 'nivel' in v and v['nivel'] not in NIVEIS:
                    _erro(onde + '.nivel', 'nível %r inválido' % v['nivel'])
                causas = _campo(v, 'causas', 'lista', onde, opcional=True) or []
                if not all(isinstance(x, str) for x in causas):
                    _erro(onde + '.causas', 'deveria ser uma lista de textos')

    def _validar_escrita(self, lst):
        ids = set()
        for k, e in enumerate(lst):
            onde = 'escrita[%d]' % k
            i = _campo(e, 'id', 'texto', onde)
            if i in ids:
                _erro(onde, 'id "%s" repetido' % i)
            ids.add(i)
            onde = 'escrita "%s"' % i
            if _campo(e, 'tipo', 'texto', onde) not in TIPOS_ESCRITA:
                _erro(onde + '.tipo', 'tipo %r inválido (use %s)' % (e['tipo'], ', '.join(TIPOS_ESCRITA)))
            _campo(e, 'nome', 'texto', onde)
            hex_bytes(_campo(e, 'pedido', 'texto', onde), onde + '.pedido')
            for j, a in enumerate(_campo(e, 'antes', 'lista', onde, opcional=True) or []):
                hex_bytes(a, '%s.antes[%d]' % (onde, j))
            if 'perigoso' in e:
                _campo(e, 'perigoso', 'bool', onde)
            _campo(e, 'aviso', 'texto', onde, opcional=True)
            if _campo(e, 'nivel', 'texto', onde) not in NIVEIS:
                _erro(onde + '.nivel', 'nível %r inválido' % e['nivel'])


def carregar(caminho=None):
    """Lê e valida um perfil (padrão: perfis/sagem-sl96/perfil.json). PerfilInvalido se estiver errado."""
    caminho = caminho or PERFIL_PADRAO
    try:
        with open(caminho, encoding='utf-8') as f:
            dados = json.load(f)
    except OSError as e:
        raise PerfilInvalido('%s: não foi possível ler (%s)' % (caminho, e))
    except ValueError as e:
        raise PerfilInvalido('%s: JSON inválido (%s)' % (caminho, e))
    return Perfil(dados, caminho)


# ---- conversão ----
def numero(perfil, m, raw):
    """Valor numérico da métrica. Bit/enum devolvem o próprio bruto (0/1 ou o código)."""
    if raw is None:
        return None
    if m['tipo'] in ('bit', 'enum'):
        return raw
    if m['tipo'] == 'curva':
        c = perfil.curvas[m['curva']]
        v = sum(k * raw ** n for n, k in enumerate(c['coef']))
        return max(c['min'], min(c['max'], v))
    return raw * m['fator'] + m['offset']


def texto(perfil, m, raw):
    if raw is None:
        return '—'
    if m['tipo'] in ('bit', 'enum'):
        return m['valores'].get(str(raw), '?')
    v = numero(perfil, m, raw)
    return ('%.*f %s' % (perfil.decimais(m), v, m['unidade'])).strip()


def bruto(m, d):
    """Valor bruto da métrica na resposta do bloco `d` (d[0] = 1º byte de dados, contando o eco).
    IndexError se a resposta for curta demais."""
    i = m['byte']
    t = m['tipo']
    if t == 'u16':
        return d[i] << 8 | d[i + 1]
    if t == 'bit':
        return (d[i] >> m['bit']) & 1
    return d[i]


def converter(perfil, m, d):
    """-> (raw, numero, texto) da métrica m na resposta do bloco d."""
    raw = bruto(m, d)
    return raw, numero(perfil, m, raw), texto(perfil, m, raw)


def inverso(perfil, m, valor):
    """O bruto que a central mandaria para `valor` (usado pelo simulador)."""
    if m['tipo'] in ('bit', 'enum'):
        return int(valor)
    if m['tipo'] == 'curva':
        return min(range(256), key=lambda r: abs(numero(perfil, m, r) - valor))
    lim = 0xFFFF if m['tipo'] == 'u16' else 0xFF
    return max(0, min(lim, round((valor - m['offset']) / m['fator'])))


def montar_bloco(perfil, bloco, raws, tamanho=27):
    """raws: {ordem: bruto}. Bytes da resposta do bloco (d[0:2] = eco do pedido), inverso de converter()."""
    pedido = dict(perfil.blocos)[bloco]
    b = bytearray(tamanho)
    b[0:len(pedido)] = pedido
    for m in perfil.metricas:
        r = raws.get(m['ordem'])
        if m['bloco'] != bloco or r is None:
            continue
        i = m['byte']
        if m['tipo'] == 'bit':
            b[i] |= (int(r) & 1) << m['bit']
        elif m['tipo'] == 'u16':
            b[i:i + 2] = int(r).to_bytes(2, 'big')
        else:
            b[i] = int(r) & 0xFF
    return bytes(b)


# ---- condições ----
def contexto(perfil, valores):
    """valores: {ordem: número}. Devolve o conjunto de condições verdadeiras agora.
    Regra de métrica sem leitura é falsa; 'sempre' vale sempre."""
    conds = perfil.condicoes
    memo = {}

    def vale(nome):
        if nome == 'sempre' and nome not in conds:
            return True
        if nome not in memo:
            memo[nome] = all(regra(r) for r in conds[nome]['regras'])
        return memo[nome]

    def regra(r):
        if 'condicao' in r:
            return vale(r['condicao'])
        v = valores.get(perfil.metrica(r['metrica'])['ordem'])
        return v is not None and OPS[r['op']](v, r['valor'])

    c = {n for n in conds if vale(n)}
    c.add('sempre')
    return c
