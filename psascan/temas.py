"""Temas do psascan (só biblioteca padrão): validação, mescla com o Padrão, instalação e exportação.

Formato e regras: docs/TEMA.md. Um tema é só dados (cores, fontes, espessuras...), nunca código.

  carregar(origem)      valida um tema.json, um .zip, uma pasta ou bytes -> dict (como escrito, sem completar)
  completar(tema)       campos que faltam vêm do Padrão (mescla profunda)
  css(tema)             CSS (variáveis e @font-face) do painel web para um tema completo
  Repositorio(config)   temas embutidos (temas/) + importados (config/temas/), o ativo (config/tema)

Quem não passa na validação levanta TemaInvalido(motivo); o ativo inválido ou sumido vira o Padrão.
"""
import copy, io, json, math, os, re, shutil, tempfile, threading, zipfile, zlib

AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ_TEMAS = os.path.normpath(os.path.join(AQUI, '..', 'temas'))
PADRAO = os.path.join(RAIZ_TEMAS, 'padrao', 'tema.json')
CONFIG = os.path.expanduser('~/.config/psascan')

MAX_ARQUIVO = 2 * 1024 * 1024        # por arquivo da pasta arquivos/
MAX_TOTAL = 10 * 1024 * 1024         # soma dos arquivos da pasta (peles com imagens)
MAX_JSON = 256 * 1024
MAX_ENTRADAS = 64
MAX_ZIP = MAX_TOTAL + MAX_JSON + 512 * 1024
EXTENSOES = {'.woff2', '.ttf', '.png', '.webp', '.jpg'}
TIPOS = {'.woff2': 'font/woff2', '.ttf': 'font/ttf', '.png': 'image/png', '.webp': 'image/webp', '.jpg': 'image/jpeg'}
CONTRASTE_MIN = 4.5

RE_ID = re.compile(r'^[a-z0-9-]{2,32}$')
RE_COR = re.compile(r'^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$')
RE_NOME_ARQ = re.compile(r'^[A-Za-z0-9._-]{1,64}$')
RE_REF = re.compile(r'^arquivos/([A-Za-z0-9._-]{1,64})$')
RE_FONTES = re.compile(r'^[A-Za-z0-9 ,"\'-]{1,200}$')
RE_FAMILIA = re.compile(r'^[A-Za-z0-9 -]{1,40}$')
RE_PESO = re.compile(r'^[1-9]00$')

# fontes que já vêm no projeto (static/vendor): o tema as usa só citando o nome da família
EMBUTIDAS = {'Barlow Condensed': {500: 'barlow-condensed-latin-500-normal.woff2',
                                  600: 'barlow-condensed-latin-600-normal.woff2',
                                  700: 'barlow-condensed-latin-700-normal.woff2'}}
PASTA_EMBUTIDAS = '/static/vendor/barlow-condensed/'


class TemaInvalido(ValueError):
    """Tema recusado; a mensagem é o motivo, em português, para mostrar ao usuário."""


# ---------------------------------------------------------------- Padrão e mescla
_padrao_cache = None


def padrao():
    """O tema Padrão completo (cópia: pode ser alterada à vontade)."""
    global _padrao_cache
    if _padrao_cache is None:
        with open(PADRAO, encoding='utf-8') as f:
            _padrao_cache = json.load(f)
    return copy.deepcopy(_padrao_cache)


def _mesclar(base, extra):
    if isinstance(base, dict) and isinstance(extra, dict):
        out = {k: copy.deepcopy(v) for k, v in base.items()}
        for k, v in extra.items():
            out[k] = _mesclar(base[k], v) if k in base else copy.deepcopy(v)
        return out
    return copy.deepcopy(extra)


def completar(tema):
    """Campo ausente vem do Padrão. Listas (degradês) e valores trocam inteiros."""
    return _mesclar(padrao(), tema)


# ---------------------------------------------------------------- validação
def _num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def _dict(v, c):
    if not isinstance(v, dict):
        raise TemaInvalido('%s: deveria ser um objeto' % c)
    return v


def _conhecidos(obj, validos, c):
    for k in obj:
        if k not in validos:
            raise TemaInvalido('%s: campo desconhecido' % (c + '.' + k if c else k))


def _cor(v, c):
    if not isinstance(v, str) or not RE_COR.match(v):
        raise TemaInvalido('%s: cor inválida (use #rrggbb ou #rrggbbaa)' % c)


def _numero(v, c, lo, hi):
    if not _num(v):
        raise TemaInvalido('%s: deveria ser um número' % c)
    if not lo <= v <= hi:
        raise TemaInvalido('%s: número fora da faixa %s a %s' % (c, lo, hi))


def _enum(v, c, lista):
    if v not in lista or isinstance(v, bool):
        raise TemaInvalido('%s: valor fora da lista (%s)' % (c, ', '.join(map(str, lista))))


def _bool(v, c):
    if not isinstance(v, bool):
        raise TemaInvalido('%s: deveria ser verdadeiro ou falso' % c)


def _texto(v, c, maximo, minimo=0):
    if not isinstance(v, str) or not minimo <= len(v) <= maximo:
        raise TemaInvalido('%s: texto de %d a %d caracteres' % (c, minimo, maximo))


def _fontes(v, c):
    if not isinstance(v, str) or not RE_FONTES.match(v) or v.count('"') % 2 or v.count("'") % 2:
        raise TemaInvalido('%s: lista de famílias inválida (só letras, números, espaço, vírgula, aspas e hífen)' % c)


def _ref(v, c, exts, arquivos):
    m = RE_REF.match(v) if isinstance(v, str) else None
    if not m or '..' in v:
        raise TemaInvalido('%s: caminho inválido (use "arquivos/nome.ext")' % c)
    if os.path.splitext(v)[1].lower() not in exts:
        raise TemaInvalido('%s: extensão não aceita aqui (%s)' % (c, ', '.join(sorted(exts))))
    if m.group(1) not in arquivos:
        raise TemaInvalido('%s: o arquivo %s não existe no tema' % (c, v))


FAIXAS = {   # painel.espessuras
    'aro': (.5, 8), 'traco_grande': (.5, 6), 'traco_medio': (.2, 4), 'traco_fino': (.1, 2),
    'ponteiro_base': (.1, 5), 'ponteiro_ponta': (.1, 5), 'contorno_numeros': (0, 5), 'agua': (1, 10), 'alerta': (.5, 4)}


def _validar(t, arquivos):
    """Estrutura e valores de um tema (parcial ou completo). `arquivos` = nomes presentes em arquivos/."""
    pad = padrao()
    _dict(t, 'tema')
    _conhecidos(t, pad, '')
    if t.get('formato') != 1 or isinstance(t.get('formato'), bool):
        raise TemaInvalido('formato: deveria ser 1')
    if not isinstance(t.get('id'), str) or not RE_ID.match(t['id']):
        raise TemaInvalido('id: use a-z, 0-9 e hífen, de 2 a 32 caracteres')
    if 'nome' not in t:
        raise TemaInvalido('nome: campo obrigatório')
    _texto(t['nome'], 'nome', 40, 1)
    if 'descricao' in t:
        _texto(t['descricao'], 'descricao', 300)
    if 'autor' in t:
        _texto(t['autor'], 'autor', 60)
    if 'interface' in t:
        _interface(_dict(t['interface'], 'interface'), pad['interface'])
    if 'painel' in t:
        _painel(_dict(t['painel'], 'painel'), pad['painel'], arquivos)
    if t.get('mascote') is not None:
        m = _dict(t['mascote'], 'mascote')
        _conhecidos(m, ('imagem', 'posicao', 'nome'), 'mascote')
        if 'imagem' in m:
            _ref(m['imagem'], 'mascote.imagem', {'.png', '.webp', '.jpg'}, arquivos)
        if 'posicao' in m:
            _enum(m['posicao'], 'mascote.posicao', ('canto', 'centro'))
        if 'nome' in m:
            _texto(m['nome'], 'mascote.nome', 40)


def _interface(i, pad):
    _conhecidos(i, pad, 'interface')
    chaves = set(pad['escuro']) | set(pad['claro'])
    for modo in ('escuro', 'claro'):
        if modo in i:
            c = 'interface.' + modo
            cores = _dict(i[modo], c)
            _conhecidos(cores, chaves, c)
            for k, v in cores.items():
                _cor(v, c + '.' + k)
    if 'fontes' in i:
        f = _dict(i['fontes'], 'interface.fontes')
        _conhecidos(f, pad['fontes'], 'interface.fontes')
        for k, v in f.items():
            _fontes(v, 'interface.fontes.' + k)
    if 'raios' in i:
        r = _dict(i['raios'], 'interface.raios')
        _conhecidos(r, pad['raios'], 'interface.raios')
        for k, v in r.items():
            _numero(v, 'interface.raios.' + k, 0, 24)


def _painel(p, pad, arquivos):
    _conhecidos(p, pad, 'painel')
    if 'cores' in p:
        cores = _dict(p['cores'], 'painel.cores')
        _conhecidos(cores, pad['cores'], 'painel.cores')
        for k, v in cores.items():
            c = 'painel.cores.' + k
            if isinstance(pad['cores'][k], list):
                n = len(pad['cores'][k])
                if not isinstance(v, list) or len(v) != n:
                    raise TemaInvalido('%s: deveria ser uma lista de %d cores' % (c, n))
                for j, x in enumerate(v):
                    _cor(x, '%s[%d]' % (c, j))
            else:
                _cor(v, c)
    if 'fonte' in p:
        f = _dict(p['fonte'], 'painel.fonte')
        _conhecidos(f, pad['fonte'], 'painel.fonte')
        if 'web' in f:
            w = _dict(f['web'], 'painel.fonte.web')
            _conhecidos(w, ('familia', 'arquivos'), 'painel.fonte.web')
            if 'familia' in w and (not isinstance(w['familia'], str) or not RE_FAMILIA.match(w['familia'])):
                raise TemaInvalido('painel.fonte.web.familia: só letras, números, espaço e hífen (até 40)')
            for peso, ref in _dict(w.get('arquivos', {}), 'painel.fonte.web.arquivos').items():
                c = 'painel.fonte.web.arquivos.' + str(peso)
                if not RE_PESO.match(peso):
                    raise TemaInvalido('%s: o peso deve ser 100, 200 ... 900' % c)
                _ref(ref, c, {'.woff2'}, arquivos)
        if 'app' in f:
            a = _dict(f['app'], 'painel.fonte.app')
            _conhecidos(a, ('sistema', 'arquivo'), 'painel.fonte.app')
            if 'sistema' in a:
                _enum(a['sistema'], 'painel.fonte.app.sistema', ('condensada', 'padrao', 'serifada', 'mono'))
            if a.get('arquivo') is not None:
                _ref(a['arquivo'], 'painel.fonte.app.arquivo', {'.ttf'}, arquivos)
    if 'espessuras' in p:
        e = _dict(p['espessuras'], 'painel.espessuras')
        _conhecidos(e, FAIXAS, 'painel.espessuras')
        for k, v in e.items():
            _numero(v, 'painel.espessuras.' + k, *FAIXAS[k])
    if 'ponteiro' in p:
        q = _dict(p['ponteiro'], 'painel.ponteiro')
        _conhecidos(q, pad['ponteiro'], 'painel.ponteiro')
        if 'estilo' in q:
            _enum(q['estilo'], 'painel.ponteiro.estilo', ('agulha', 'seta', 'barra'))
        for k in ('brilho', 'sombra'):
            if k in q:
                _numero(q[k], 'painel.ponteiro.' + k, 0, 1)
        for k in ('raio_inicio', 'raio_ponta'):
            if k in q:
                _numero(q[k], 'painel.ponteiro.' + k, 0, 95)
    if 'escala' in p:
        e = _dict(p['escala'], 'painel.escala')
        _conhecidos(e, pad['escala'], 'painel.escala')
        if 'niveis' in e:
            _enum(e['niveis'], 'painel.escala.niveis', (2, 3))
        for k in ('pintar_passou', 'numeros_por_cima'):
            if k in e:
                _bool(e[k], 'painel.escala.' + k)
    if 'raios' in p:
        r = _dict(p['raios'], 'painel.raios')
        _conhecidos(r, pad['raios'], 'painel.raios')
        for k, v in r.items():
            _numero(v, 'painel.raios.' + k, 20 if k == 'miolo' else 0, 60 if k == 'miolo' else 24)
    if 'peles' in p:
        _peles(_dict(p['peles'], 'painel.peles'), pad, arquivos)
    if 'animacao' in p:
        a = _dict(p['animacao'], 'painel.animacao')
        _conhecidos(a, pad['animacao'], 'painel.animacao')
        if 'ponteiro' in a:
            _enum(a['ponteiro'], 'painel.animacao.ponteiro', ('mola', 'suave', 'nenhuma'))
        if 'rapidez' in a:
            _numero(a['rapidez'], 'painel.animacao.rapidez', 1, 20)


IMG = {'.png', '.webp', '.jpg'}


def _img(v, c, arquivos):
    _ref(v, c, IMG, arquivos)


def _peles(pe, pad, arquivos):
    """painel.peles (docs/TEMA.md): o desenho de cada tipo de widget, só com dados."""
    _conhecidos(pe, ('mostrador', 'barra', 'numero', 'numero_historico', 'luz', 'estado', 'falhas'), 'painel.peles')

    def fonte_e_brilho(d, c):
        if d.get('fonte') is not None:
            _ref(d['fonte'], c + '.fonte', {'.woff2'}, arquivos)
        if d.get('fonte_app') is not None:
            _ref(d['fonte_app'], c + '.fonte_app', {'.ttf'}, arquivos)
        if 'brilho' in d:
            _numero(d['brilho'], c + '.brilho', 0, 1)
        if 'apagados' in d:
            _bool(d['apagados'], c + '.apagados')

    if 'mostrador' in pe:
        c = 'painel.peles.mostrador'
        m = _dict(pe['mostrador'], c)
        _conhecidos(m, ('angulo_inicio', 'angulo_fim', 'imagens', 'desenhar', 'arco', 'digital', 'cores'), c)
        for k in ('angulo_inicio', 'angulo_fim'):
            if k in m:
                _numero(m[k], c + '.' + k, -180, 360)
        if 'angulo_inicio' in m and 'angulo_fim' in m and m['angulo_fim'] <= m['angulo_inicio']:
            raise TemaInvalido(c + '.angulo_fim: tem de ser maior que angulo_inicio')
        if 'imagens' in m:
            im = _dict(m['imagens'], c + '.imagens')
            _conhecidos(im, ('fundo', 'agulha', 'miolo', 'vidro', 'mascara_arco'), c + '.imagens')
            for k in ('fundo', 'miolo', 'vidro', 'mascara_arco'):
                if k in im:
                    _img(im[k], c + '.imagens.' + k, arquivos)
            if 'agulha' in im:
                ag = _dict(im['agulha'], c + '.imagens.agulha')
                _conhecidos(ag, ('arquivo', 'pivo', 'comprimento'), c + '.imagens.agulha')
                _img(ag.get('arquivo'), c + '.imagens.agulha.arquivo', arquivos)
                pv = ag.get('pivo', [0.5, 0.5])
                if not isinstance(pv, list) or len(pv) != 2:
                    raise TemaInvalido(c + '.imagens.agulha.pivo: deveria ser [x, y] entre 0 e 1')
                for j, x in enumerate(pv):
                    _numero(x, '%s.imagens.agulha.pivo[%d]' % (c, j), 0, 1)
                if 'comprimento' in ag:
                    _numero(ag['comprimento'], c + '.imagens.agulha.comprimento', 0.1, 1)
        if 'desenhar' in m:
            d = _dict(m['desenhar'], c + '.desenhar')
            _conhecidos(d, ('aro', 'escala', 'numeros', 'ponteiro', 'miolo'), c + '.desenhar')
            for k, v in d.items():
                _bool(v, c + '.desenhar.' + k)
        if 'arco' in m:
            a = _dict(m['arco'], c + '.arco')
            _conhecidos(a, ('mostrar', 'raio', 'espessura', 'estilo', 'segmentos', 'cor', 'cor_alerta'), c + '.arco')
            if 'mostrar' in a:
                _bool(a['mostrar'], c + '.arco.mostrar')
            for k, lo, hi in (('raio', 20, 98), ('espessura', 1, 30), ('segmentos', 8, 120)):
                if k in a:
                    _numero(a[k], c + '.arco.' + k, lo, hi)
            if 'estilo' in a:
                _enum(a['estilo'], c + '.arco.estilo', ('continuo', 'segmentos'))
            for k in ('cor', 'cor_alerta'):
                if k in a:
                    _cor(a[k], c + '.arco.' + k)
        if 'digital' in m:
            d = _dict(m['digital'], c + '.digital')
            _conhecidos(d, ('posicao', 'tamanho', 'fonte', 'fonte_app', 'cor', 'brilho', 'apagados'), c + '.digital')
            if 'posicao' in d:
                ps = d['posicao']
                if not isinstance(ps, list) or len(ps) != 2:
                    raise TemaInvalido(c + '.digital.posicao: deveria ser [x, y] entre 0 e 200')
                for j, x in enumerate(ps):
                    _numero(x, '%s.digital.posicao[%d]' % (c, j), 0, 200)
            if 'tamanho' in d:
                _numero(d['tamanho'], c + '.digital.tamanho', 6, 80)
            if 'cor' in d:
                _cor(d['cor'], c + '.digital.cor')
            fonte_e_brilho(d, c + '.digital')
        if 'cores' in m:
            # cores só deste tipo de widget (ex.: números escuros num mostrador iluminado por trás)
            k_ = _dict(m['cores'], c + '.cores')
            _conhecidos(k_, pad['cores'], c + '.cores')
            for k, v in k_.items():
                if isinstance(pad['cores'][k], list):
                    if not isinstance(v, list) or len(v) != len(pad['cores'][k]):
                        raise TemaInvalido('%s.cores.%s: deveria ser uma lista de %d cores' % (c, k, len(pad['cores'][k])))
                    for j, x in enumerate(v):
                        _cor(x, '%s.cores.%s[%d]' % (c, k, j))
                else:
                    _cor(v, '%s.cores.%s' % (c, k))
    if 'barra' in pe:
        c = 'painel.peles.barra'
        b = _dict(pe['barra'], c)
        _conhecidos(b, ('estilo', 'segmentos', 'espaco', 'raio', 'imagens'), c)
        if 'estilo' in b:
            _enum(b['estilo'], c + '.estilo', ('continua', 'segmentos'))
        for k, lo, hi in (('segmentos', 4, 60), ('espaco', 0, 0.6), ('raio', 0, 12)):
            if k in b:
                _numero(b[k], c + '.' + k, lo, hi)
        if 'imagens' in b:
            im = _dict(b['imagens'], c + '.imagens')
            _conhecidos(im, ('trilho', 'cheia'), c + '.imagens')
            for k, v in im.items():
                _img(v, c + '.imagens.' + k, arquivos)
    if 'numero' in pe:
        c = 'painel.peles.numero'
        n = _dict(pe['numero'], c)
        _conhecidos(n, ('fonte', 'fonte_app', 'brilho', 'apagados'), c)
        fonte_e_brilho(n, c)
    if 'numero_historico' in pe:
        c = 'painel.peles.numero_historico'
        n = _dict(pe['numero_historico'], c)
        _conhecidos(n, ('linha', 'preencher'), c)
        if 'linha' in n:
            _numero(n['linha'], c + '.linha', 0.5, 4)
        if 'preencher' in n:
            _bool(n['preencher'], c + '.preencher')
    if 'luz' in pe:
        c = 'painel.peles.luz'
        lz = _dict(pe['luz'], c)
        _conhecidos(lz, ('imagens', 'brilho'), c)
        if 'imagens' in lz:
            im = _dict(lz['imagens'], c + '.imagens')
            _conhecidos(im, ('apagada', 'acesa'), c + '.imagens')
            for k, v in im.items():
                _img(v, c + '.imagens.' + k, arquivos)
        if 'brilho' in lz:
            _numero(lz['brilho'], c + '.brilho', 0, 1)
    for t in ('estado', 'falhas'):
        if t in pe:
            c = 'painel.peles.' + t
            d = _dict(pe[t], c)
            _conhecidos(d, ('fonte', 'fonte_app', 'caixa') if t == 'estado' else ('fonte', 'fonte_app'), c)
            if d.get('fonte') is not None:
                _ref(d['fonte'], c + '.fonte', {'.woff2'}, arquivos)
            if d.get('fonte_app') is not None:
                _ref(d['fonte_app'], c + '.fonte_app', {'.ttf'}, arquivos)
            if 'caixa' in d:
                _bool(d['caixa'], c + '.caixa')


def luminancia(cor):
    """Luminância relativa WCAG de #rrggbb[aa] (o alfa é ignorado)."""
    def lin(x):
        x /= 255
        return x / 12.92 if x <= .03928 else ((x + .055) / 1.055) ** 2.4
    r, g, b = (int(cor[i:i + 2], 16) for i in (1, 3, 5))
    return .2126 * lin(r) + .7152 * lin(g) + .0722 * lin(b)


def contraste(a, b):
    la, lb = sorted((luminancia(a), luminancia(b)), reverse=True)
    return (la + .05) / (lb + .05)


def _checar_contraste(completo):
    pares = [('painel.cores.texto', completo['painel']['cores']['texto'], 'painel.cores.fundo', completo['painel']['cores']['fundo'])]
    for modo in ('escuro', 'claro'):
        c = completo['interface'][modo]
        pares.append(('interface.%s.ink' % modo, c['ink'], 'interface.%s.bg' % modo, c['bg']))
    for n1, c1, n2, c2 in pares:
        r = contraste(c1, c2)
        if r < CONTRASTE_MIN:
            raise TemaInvalido('contraste insuficiente entre %s e %s (%.2f:1; o mínimo é 4,5:1)'
                               % (n1, n2, r))


# ---------------------------------------------------------------- leitura de pacotes
class Pacote:
    """Tema lido e validado: dict como escrito, os bytes do tema.json e os arquivos (nome -> bytes)."""
    def __init__(self, tema, json_bytes, arquivos):
        self.tema, self.json_bytes, self.arquivos = tema, json_bytes, arquivos


def _zip(dados):
    if len(dados) > MAX_ZIP:
        raise TemaInvalido('arquivo grande demais (máximo %d MB)' % (MAX_ZIP // 1048576 + 1))
    try:
        zf = zipfile.ZipFile(io.BytesIO(dados))
        infos = zf.infolist()
    except (zipfile.BadZipFile, NotImplementedError, EOFError) as e:
        raise TemaInvalido('zip inválido: %s' % e)
    if len(infos) > MAX_ENTRADAS:
        raise TemaInvalido('zip com arquivos demais (máximo %d)' % MAX_ENTRADAS)
    json_bytes, arquivos, total, vistos = None, {}, 0, set()
    for i in infos:
        n = i.filename
        if n in vistos:
            raise TemaInvalido('zip com nome repetido: %s' % n)
        vistos.add(n)
        if n == 'arquivos/' and i.is_dir():
            continue
        if '\\' in n or n.startswith('/') or '..' in n or ':' in n:
            raise TemaInvalido('caminho proibido no zip: %s' % n)
        if i.flag_bits & 1:
            raise TemaInvalido('zip criptografado não é aceito')
        if (i.external_attr >> 16) & 0o170000 == 0o120000:
            raise TemaInvalido('link simbólico proibido no zip: %s' % n)
        if n == 'tema.json':
            limite = MAX_JSON
        else:
            m = RE_REF.match(n)
            if not m or os.path.splitext(n)[1].lower() not in EXTENSOES:
                raise TemaInvalido('arquivo não permitido no zip: %s (só tema.json e arquivos/ com %s)'
                                   % (n, ', '.join(sorted(EXTENSOES))))
            limite = MAX_ARQUIVO
        if i.file_size > limite:
            raise TemaInvalido('arquivo grande demais no zip: %s (máximo %d bytes)' % (n, limite))
        total += i.file_size
        if n != 'tema.json' and total > MAX_TOTAL + MAX_JSON:
            raise TemaInvalido('arquivos do tema somam mais de %d MB' % (MAX_TOTAL // 1048576))
        try:
            with zf.open(i) as f:
                b = f.read(i.file_size + 1)
        except (zipfile.BadZipFile, NotImplementedError, RuntimeError, zlib.error, EOFError) as e:
            raise TemaInvalido('não consegui ler %s do zip: %s' % (n, e))
        if len(b) != i.file_size:
            raise TemaInvalido('tamanho de %s não confere com o declarado no zip' % n)
        if n == 'tema.json':
            json_bytes = b
        else:
            arquivos[m.group(1)] = b
    if json_bytes is None:
        raise TemaInvalido('o zip não tem tema.json na raiz')
    return json_bytes, arquivos


def _pasta(pasta):
    try:
        nomes = os.listdir(pasta)
    except OSError as e:
        raise TemaInvalido('não consegui abrir a pasta: %s' % e)
    for n in nomes:
        if n not in ('tema.json', 'arquivos'):
            raise TemaInvalido('arquivo não permitido na pasta do tema: %s' % n)
    if 'tema.json' not in nomes:
        raise TemaInvalido('a pasta não tem tema.json')
    json_bytes = _ler_regular(os.path.join(pasta, 'tema.json'), MAX_JSON)
    arquivos, total = {}, 0
    sub = os.path.join(pasta, 'arquivos')
    if 'arquivos' in nomes:
        if os.path.islink(sub) or not os.path.isdir(sub):
            raise TemaInvalido('arquivos deve ser uma pasta')
        for n in sorted(os.listdir(sub)):
            if not RE_NOME_ARQ.match(n) or os.path.splitext(n)[1].lower() not in EXTENSOES:
                raise TemaInvalido('arquivo não permitido em arquivos/: %s' % n)
            arquivos[n] = _ler_regular(os.path.join(sub, n), MAX_ARQUIVO)
            total += len(arquivos[n])
            if total > MAX_TOTAL:
                raise TemaInvalido('arquivos do tema somam mais de %d MB' % (MAX_TOTAL // 1048576))
    return json_bytes, arquivos


def _ler_regular(caminho, limite):
    try:
        if os.path.islink(caminho) or not os.path.isfile(caminho):
            raise TemaInvalido('%s não é um arquivo comum' % os.path.basename(caminho))
        if os.path.getsize(caminho) > limite:
            raise TemaInvalido('%s: grande demais (máximo %d bytes)' % (os.path.basename(caminho), limite))
        with open(caminho, 'rb') as f:
            return f.read()
    except OSError as e:
        raise TemaInvalido('não consegui ler %s: %s' % (os.path.basename(caminho), e))


def _recusar_constante(x):
    raise ValueError('constante %s não é permitida' % x)


def carregar_pacote(origem):
    """Lê e valida. `origem`: bytes (tema.json ou .zip), caminho de .json/.zip ou de uma pasta de tema."""
    if isinstance(origem, (bytes, bytearray)):
        dados = bytes(origem)
        json_bytes, arquivos = _zip(dados) if dados[:4] == b'PK\x03\x04' else (dados, {})
    else:
        caminho = os.fspath(origem)
        if os.path.isdir(caminho):
            json_bytes, arquivos = _pasta(caminho)
        else:
            if os.path.basename(caminho) == 'tema.json' and os.path.isdir(os.path.join(os.path.dirname(caminho), 'arquivos')):
                json_bytes, arquivos = _pasta(os.path.dirname(caminho))   # tema.json ao lado da sua pasta arquivos/
            else:
                dados = _ler_regular(caminho, MAX_ZIP)
                json_bytes, arquivos = _zip(dados) if dados[:4] == b'PK\x03\x04' else (dados, {})
    if len(json_bytes) > MAX_JSON:
        raise TemaInvalido('tema.json grande demais (máximo %d KB)' % (MAX_JSON // 1024))
    try:
        t = json.loads(json_bytes.decode('utf-8'), parse_constant=_recusar_constante)
    except (UnicodeDecodeError, ValueError) as e:
        raise TemaInvalido('JSON inválido: %s' % e)
    _validar(t, set(arquivos))
    _checar_contraste(completar(t))
    return Pacote(t, json_bytes, arquivos)


def carregar(origem):
    """O tema validado (dict, como escrito). Levanta TemaInvalido(motivo)."""
    return carregar_pacote(origem).tema


# ---------------------------------------------------------------- CSS do painel web
def css(tema):
    """CSS do tema completo: variáveis da interface (escuro e claro), do painel (--painel-*) e @font-face."""
    i, p, tid = tema['interface'], tema['painel'], tema['id']

    def bloco(sel, d):
        return sel + '{' + ''.join('--%s:%s;' % kv for kv in d.items()) + '}'
    raiz = dict(i['escuro'])
    raiz.update(i['fontes'])
    raiz.update({k: '%gpx' % v for k, v in i['raios'].items()})
    raiz.update({'painel-' + k.replace('_', '-'): v for k, v in p['cores'].items() if isinstance(v, str)})
    out = [bloco(':root', raiz), bloco(':root[data-theme="light"]', i['claro'])]
    web = p['fonte']['web']
    fam, feitos = web['familia'], set()
    for peso, ref in sorted(web.get('arquivos', {}).items()):
        feitos.add((fam, int(peso)))
        out.append('@font-face{font-family:"%s";font-weight:%s;font-display:swap;src:url(/tema/%s/%s) format("woff2")}'
                   % (fam, peso, tid, ref))
    for nome, pesos in EMBUTIDAS.items():
        for peso, arq in pesos.items():
            if (nome, peso) not in feitos:
                out.append('@font-face{font-family:"%s";font-weight:%d;font-display:swap;src:url(%s%s) format("woff2")}'
                           % (nome, peso, PASTA_EMBUTIDAS, arq))
    return '\n'.join(out) + '\n'


# ---------------------------------------------------------------- repositório
class Repositorio:
    """Temas embutidos (temas/), importados (<config>/temas/<id>/) e o ativo (<config>/tema, só o id)."""

    def __init__(self, config=CONFIG, embutidos=RAIZ_TEMAS):
        self.config = config
        self.embutidos = embutidos
        self.pasta_usuario = os.path.join(config, 'temas')
        self.arq_ativo = os.path.join(config, 'tema')
        self.versao = 0               # sobe a cada mudança (ativar/instalar): a web avisa as páginas abertas
        self._trava = threading.RLock()

    def _pasta(self, tid):
        if not isinstance(tid, str) or not RE_ID.match(tid):
            return None
        for base in (self.embutidos, self.pasta_usuario):
            p = os.path.join(base, tid)
            if os.path.isfile(os.path.join(p, 'tema.json')):
                return p
        return None

    def embutido(self, tid):
        return os.path.isfile(os.path.join(self.embutidos, tid, 'tema.json'))

    def pacote(self, tid):
        p = self._pasta(tid)
        if not p:
            raise KeyError(tid)
        pac = carregar_pacote(p)
        if pac.tema['id'] != tid:
            raise TemaInvalido('id: %s é diferente do nome da pasta (%s)' % (pac.tema['id'], tid))
        return pac

    def listar(self):
        ativo = self.ativo_id()
        out, vistos = [], set()
        for base in (self.embutidos, self.pasta_usuario):
            try:
                nomes = sorted(os.listdir(base))
            except OSError:
                continue
            for n in nomes:
                if n in vistos or n.startswith('.') or not os.path.isdir(os.path.join(base, n)):
                    continue
                vistos.add(n)
                item = {'id': n, 'nome': n, 'descricao': '', 'autor': '', 'embutido': base == self.embutidos,
                        'ativo': n == ativo, 'valido': True, 'erro': None}
                try:
                    t = self.pacote(n).tema
                    item.update(nome=t['nome'], descricao=t.get('descricao', ''), autor=t.get('autor', ''))
                except TemaInvalido as e:
                    item.update(valido=False, erro=str(e))
                out.append(item)
        return out

    def ativo_id(self):
        try:
            with open(self.arq_ativo, encoding='utf-8') as f:
                tid = f.read().strip()
        except OSError:
            return 'padrao'
        return tid if RE_ID.match(tid) else 'padrao'

    def ativo_com_erro(self):
        """(tema completo, motivo): se o ativo sumiu ou é inválido, volta ao Padrão e diz o porquê."""
        tid = self.ativo_id()
        try:
            return completar(self.pacote(tid).tema), None
        except KeyError:
            motivo = 'o tema "%s" não existe mais' % tid
        except TemaInvalido as e:
            motivo = 'o tema "%s" é inválido: %s' % (tid, e)
        return completar(padrao()), motivo

    def ativo(self):
        return self.ativo_com_erro()[0]

    def ativar(self, tid):
        with self._trava:
            self.pacote(tid)          # KeyError se não existe, TemaInvalido se não passa
            os.makedirs(self.config, exist_ok=True)
            _gravar_atomico(self.arq_ativo, (tid + '\n').encode())
            self.versao += 1

    def instalar(self, dados):
        """Valida e grava em <config>/temas/<id>/ (atômico). Devolve o tema como escrito."""
        pac = carregar_pacote(bytes(dados))
        tid = pac.tema['id']
        if self.embutido(tid):
            raise TemaInvalido('id: "%s" já é um tema que vem com o projeto; escolha outro id' % tid)
        with self._trava:
            os.makedirs(self.pasta_usuario, exist_ok=True)
            tmp = tempfile.mkdtemp(prefix='.novo-', dir=self.pasta_usuario)
            try:
                with open(os.path.join(tmp, 'tema.json'), 'wb') as f:
                    f.write(pac.json_bytes)
                if pac.arquivos:
                    os.mkdir(os.path.join(tmp, 'arquivos'))
                    for n, b in pac.arquivos.items():
                        with open(os.path.join(tmp, 'arquivos', n), 'wb') as f:
                            f.write(b)
                destino, velho = os.path.join(self.pasta_usuario, tid), None
                if os.path.exists(destino):
                    velho = tempfile.mkdtemp(prefix='.velho-', dir=self.pasta_usuario)
                    os.rmdir(velho)
                    os.rename(destino, velho)
                os.rename(tmp, destino)
                tmp = None
                if velho:
                    shutil.rmtree(velho, ignore_errors=True)
            finally:
                if tmp:
                    shutil.rmtree(tmp, ignore_errors=True)
            self.versao += 1
        return pac.tema

    def exportar(self, tid):
        """Zip com tema.json e arquivos/ do tema `tid`."""
        pac = self.pacote(tid)
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as zf:
            zf.writestr(zipfile.ZipInfo('tema.json', (1980, 1, 1, 0, 0, 0)), pac.json_bytes, zipfile.ZIP_DEFLATED)
            for n in sorted(pac.arquivos):
                zf.writestr(zipfile.ZipInfo('arquivos/' + n, (1980, 1, 1, 0, 0, 0)), pac.arquivos[n], zipfile.ZIP_DEFLATED)
        return buf.getvalue()

    def arquivo(self, tid, nome):
        """Caminho de um arquivo de arquivos/ do tema, ou None (sem travessia de caminho)."""
        p = self._pasta(tid)
        if not p or not isinstance(nome, str) or not RE_NOME_ARQ.match(nome) \
                or os.path.splitext(nome)[1].lower() not in EXTENSOES:
            return None
        alvo = os.path.realpath(os.path.join(p, 'arquivos', nome))
        if not alvo.startswith(os.path.realpath(os.path.join(p, 'arquivos')) + os.sep) or not os.path.isfile(alvo):
            return None
        return alvo


def _gravar_atomico(caminho, dados):
    fd, tmp = tempfile.mkstemp(prefix='.tmp-', dir=os.path.dirname(caminho))
    try:
        with os.fdopen(fd, 'wb') as f:
            f.write(dados)
        os.replace(tmp, caminho)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
