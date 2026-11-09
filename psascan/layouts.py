"""Layouts da tela personalizável (só biblioteca padrão): validação e repositório.

Formato e regras: docs/LAYOUT.md. Um layout é só dados (JSON): quais widgets aparecem e onde. Os tamanhos,
métricas aceitas e opções de cada tipo vêm de static/widgets/catalogo.json (fonte única, a mesma do app).

  carregar(origem)         valida um layout (bytes, texto ou caminho) -> dict como escrito
  validar(layout)          idem, para um dict já lido
  categoria(tipo_perfil)   'numerica' | 'discreta' | 'bit' (como o catálogo agrupa as métricas)
  aceita(tipo, tipo_perfil)  o tipo de widget aceita a métrica deste tipo do perfil?
  Repositorio(config)      prontos (layouts/) + do usuário (config/layouts/), o ativo (config/layout)

Quem não passa na validação levanta LayoutInvalido(motivo), com o caminho do campo (ex. "widgets[2].largura").
Métrica que não existe no perfil NÃO invalida o layout (o mesmo layout serve a outro perfil): a tela mostra
o widget como indisponível.
"""
import json, math, os, re, tempfile, threading

AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ_LAYOUTS = os.path.normpath(os.path.join(AQUI, '..', 'layouts'))
CATALOGO = os.path.join(AQUI, 'static', 'widgets', 'catalogo.json')
CONFIG = os.path.expanduser('~/.config/psascan')
PADRAO = 'painel'

MAX_JSON = 256 * 1024
MAX_WIDGETS = 40
COLUNAS = (4, 16)
LINHAS = (3, 12)

RE_ID = re.compile(r'^[a-z0-9-]{2,32}$')
RE_ID_WIDGET = re.compile(r'^[a-z0-9_-]{1,32}$')
RE_METRICA = re.compile(r'^[a-z0-9_]{1,40}$')
ORIENTACOES = ('paisagem', 'retrato', 'qualquer')

CATEGORIAS = {'u8': 'numerica', 'u16': 'numerica', 'curva': 'numerica', 'bit': 'bit', 'enum': 'discreta'}


class LayoutInvalido(ValueError):
    """Layout recusado; a mensagem é o motivo, em português, começando pelo caminho do campo."""


_catalogo_cache = None


def catalogo():
    global _catalogo_cache
    if _catalogo_cache is None:
        with open(CATALOGO, encoding='utf-8') as f:
            _catalogo_cache = json.load(f)
    return _catalogo_cache


def categoria(tipo_perfil):
    return CATEGORIAS.get(tipo_perfil)


def aceita(tipo, tipo_perfil):
    """O widget `tipo` serve para uma métrica do perfil deste `tipo_perfil` (u8, u16, curva, bit, enum)?
    `bit` conta como discreta também (o `numero` e o `estado` mostram o texto de um bit)."""
    cat = categoria(tipo_perfil)
    aceitas = catalogo()['tipos'][tipo]['metricas']
    return cat in aceitas or (cat == 'bit' and 'discreta' in aceitas)


# ---------------------------------------------------------------- validação
def _num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def _int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def _dict(v, c):
    if not isinstance(v, dict):
        raise LayoutInvalido('%s: deveria ser um objeto' % c)
    return v


def _conhecidos(obj, validos, c):
    for k in obj:
        if k not in validos:
            raise LayoutInvalido('%s: campo desconhecido' % (c + '.' + k if c else k))


def _texto(v, c, maximo, minimo=0):
    if not isinstance(v, str) or not minimo <= len(v) <= maximo:
        raise LayoutInvalido('%s: texto de %d a %d caracteres' % (c, minimo, maximo))


def _inteiro(v, c, lo, hi):
    if not _int(v):
        raise LayoutInvalido('%s: deveria ser um número inteiro' % c)
    if not lo <= v <= hi:
        raise LayoutInvalido('%s: fora da faixa %d a %d' % (c, lo, hi))


def _opcao(v, esp, c):
    """Valida um valor de opção contra a descrição do catálogo (tipo, min/max, valores, campos)."""
    t = esp['tipo']
    if t == 'bool':
        if not isinstance(v, bool):
            raise LayoutInvalido('%s: deveria ser verdadeiro ou falso' % c)
    elif t == 'inteiro':
        if not _int(v):
            raise LayoutInvalido('%s: deveria ser um número inteiro' % c)
        _faixa(v, esp, c)
    elif t == 'numero':
        if not _num(v):
            raise LayoutInvalido('%s: deveria ser um número' % c)
        _faixa(v, esp, c)
    elif t == 'lista':
        if isinstance(v, bool) or v not in esp['valores']:
            raise LayoutInvalido('%s: valor fora da lista (%s)' % (c, ', '.join(esp['valores'])))
    elif t == 'texto':
        _texto(v, c, esp.get('max', 200))
    elif t == 'metrica':
        if not isinstance(v, str) or not RE_METRICA.match(v):
            raise LayoutInvalido('%s: id de métrica inválido (use a-z, 0-9 e _)' % c)
    elif t == 'objeto':
        o = _dict(v, c)
        campos = esp['campos']
        _conhecidos(o, campos, c)
        for k, sub in campos.items():
            if k in o:
                _opcao(o[k], sub, c + '.' + k)
            elif sub.get('obrigatorio'):
                raise LayoutInvalido('%s.%s: campo obrigatório' % (c, k))
    else:   # catálogo com tipo que este código não conhece
        raise LayoutInvalido('%s: tipo de opção desconhecido no catálogo (%s)' % (c, t))


def _faixa(v, esp, c):
    if 'min' in esp and v < esp['min']:
        raise LayoutInvalido('%s: fora da faixa (mínimo %s)' % (c, esp['min']))
    if 'max' in esp and v > esp['max']:
        raise LayoutInvalido('%s: fora da faixa (máximo %s)' % (c, esp['max']))
    if 'min_exclusivo' in esp and v <= esp['min_exclusivo']:
        raise LayoutInvalido('%s: deveria ser maior que %s' % (c, esp['min_exclusivo']))


def validar(layout):
    """Estrutura e valores de um layout. Devolve o próprio dict; levanta LayoutInvalido."""
    cat = catalogo()['tipos']
    L = _dict(layout, 'layout')
    _conhecidos(L, ('formato', 'id', 'nome', 'descricao', 'grade', 'orientacao', 'widgets'), '')
    if not _int(L.get('formato')) or L['formato'] != 1:
        raise LayoutInvalido('formato: deveria ser 1')
    if not isinstance(L.get('id'), str) or not RE_ID.match(L['id']):
        raise LayoutInvalido('id: use a-z, 0-9 e hífen, de 2 a 32 caracteres')
    if 'nome' not in L:
        raise LayoutInvalido('nome: campo obrigatório')
    _texto(L['nome'], 'nome', 40, 1)
    if 'descricao' in L:
        _texto(L['descricao'], 'descricao', 300)
    if 'orientacao' in L and (L['orientacao'] not in ORIENTACOES or isinstance(L['orientacao'], bool)):
        raise LayoutInvalido('orientacao: valor fora da lista (%s)' % ', '.join(ORIENTACOES))
    if 'grade' not in L:
        raise LayoutInvalido('grade: campo obrigatório')
    g = _dict(L['grade'], 'grade')
    _conhecidos(g, ('colunas', 'linhas'), 'grade')
    for k, (lo, hi) in (('colunas', COLUNAS), ('linhas', LINHAS)):
        if k not in g:
            raise LayoutInvalido('grade.%s: campo obrigatório' % k)
        _inteiro(g[k], 'grade.' + k, lo, hi)
    if 'widgets' not in L:
        raise LayoutInvalido('widgets: campo obrigatório')
    if not isinstance(L['widgets'], list):
        raise LayoutInvalido('widgets: deveria ser uma lista')
    if len(L['widgets']) > MAX_WIDGETS:
        raise LayoutInvalido('widgets: no máximo %d widgets' % MAX_WIDGETS)
    ids, ocupado = set(), {}
    for i, w in enumerate(L['widgets']):
        c = 'widgets[%d]' % i
        _dict(w, c)
        _conhecidos(w, ('id', 'tipo', 'metrica', 'x', 'y', 'largura', 'altura', 'titulo', 'opcoes'), c)
        if not isinstance(w.get('id'), str) or not RE_ID_WIDGET.match(w['id']):
            raise LayoutInvalido('%s.id: use a-z, 0-9, _ e hífen, de 1 a 32 caracteres' % c)
        if w['id'] in ids:
            raise LayoutInvalido('%s.id: "%s" repetido (cada widget precisa de um id único)' % (c, w['id']))
        ids.add(w['id'])
        if not isinstance(w.get('tipo'), str) or w['tipo'] not in cat:
            raise LayoutInvalido('%s.tipo: tipo desconhecido (%s)' % (c, ', '.join(cat)))
        tipo = cat[w['tipo']]
        op = w.get('opcoes', {})
        _dict(op, c + '.opcoes')
        if 'metrica' in w:
            if not tipo['metricas'] and not tipo.get('sem_metrica_se'):
                raise LayoutInvalido('%s.metrica: o widget "%s" não usa métrica' % (c, w['tipo']))
            if not isinstance(w['metrica'], str) or not RE_METRICA.match(w['metrica']):
                raise LayoutInvalido('%s.metrica: id de métrica inválido (use a-z, 0-9 e _)' % c)
        elif tipo['metricas']:
            sem = tipo.get('sem_metrica_se') or {}
            if not (sem and all(op.get(k) == v for k, v in sem.items())):
                raise LayoutInvalido('%s.metrica: campo obrigatório para o widget "%s"' % (c, w['tipo']))
        for k in ('x', 'y', 'largura', 'altura'):
            if k not in w:
                raise LayoutInvalido('%s.%s: campo obrigatório' % (c, k))
            _inteiro(w[k], '%s.%s' % (c, k), 0, 10 ** 6)
        for k, eixo, lim in (('largura', 0, g['colunas']), ('altura', 1, g['linhas'])):
            lo, hi = tipo['minimo'][eixo], tipo['maximo'][eixo]
            if not lo <= w[k] <= hi:
                raise LayoutInvalido('%s.%s: o widget "%s" aceita de %d a %d células' % (c, k, w['tipo'], lo, hi))
        if w['largura'] < 1 or w['altura'] < 1:
            raise LayoutInvalido('%s: tamanho vazio' % c)
        if w['x'] + w['largura'] > g['colunas']:
            raise LayoutInvalido('%s.x: o widget sai da grade (colunas 0 a %d)' % (c, g['colunas'] - 1))
        if w['y'] + w['altura'] > g['linhas']:
            raise LayoutInvalido('%s.y: o widget sai da grade (linhas 0 a %d)' % (c, g['linhas'] - 1))
        if 'titulo' in w:
            _texto(w['titulo'], c + '.titulo', 40)
        _conhecidos(op, tipo['opcoes'], c + '.opcoes')
        for k, v in op.items():
            _opcao(v, tipo['opcoes'][k], '%s.opcoes.%s' % (c, k))
        for yy in range(w['y'], w['y'] + w['altura']):
            for xx in range(w['x'], w['x'] + w['largura']):
                if (xx, yy) in ocupado:
                    raise LayoutInvalido('%s: sobrepõe o widget "%s"' % (c, ocupado[(xx, yy)]))
                ocupado[(xx, yy)] = w['id']
    return L


def carregar(origem):
    """Valida um layout vindo de bytes/texto (JSON) ou de um caminho de arquivo."""
    if isinstance(origem, str) and not origem.lstrip().startswith('{'):
        with open(origem, 'rb') as f:
            origem = f.read(MAX_JSON + 1)
    if isinstance(origem, str):
        origem = origem.encode('utf-8')
    if len(origem) > MAX_JSON:
        raise LayoutInvalido('arquivo grande demais (máximo %d KB)' % (MAX_JSON // 1024))
    try:
        L = json.loads(origem.decode('utf-8'))
    except (UnicodeDecodeError, ValueError) as e:
        raise LayoutInvalido('JSON inválido: %s' % e)
    return validar(L)


def para_bytes(layout):
    return (json.dumps(layout, ensure_ascii=False, indent=1) + '\n').encode('utf-8')


# ---------------------------------------------------------------- repositório
class Repositorio:
    """Prontos (layouts/<id>.json), do usuário (<config>/layouts/<id>.json) e o ativo (<config>/layout, só o id)."""

    def __init__(self, config=CONFIG, prontos=RAIZ_LAYOUTS):
        self.config = config
        self.prontos = prontos
        self.pasta_usuario = os.path.join(config, 'layouts')
        self.arq_ativo = os.path.join(config, 'layout')
        self.versao = 0               # sobe a cada mudança do ativo: a web avisa as páginas abertas (SSE "layout")
        self._trava = threading.RLock()

    def _arquivo(self, lid):
        if not isinstance(lid, str) or not RE_ID.match(lid):
            return None
        for base in (self.prontos, self.pasta_usuario):
            p = os.path.join(base, lid + '.json')
            if os.path.isfile(p):
                return p
        return None

    def pronto(self, lid):
        return isinstance(lid, str) and bool(RE_ID.match(lid)) and os.path.isfile(os.path.join(self.prontos, lid + '.json'))

    def layout(self, lid):
        """O layout `lid` validado. KeyError se não existe, LayoutInvalido se não passa."""
        p = self._arquivo(lid)
        if not p:
            raise KeyError(lid)
        L = carregar(p)
        if L['id'] != lid:
            raise LayoutInvalido('id: %s é diferente do nome do arquivo (%s)' % (L['id'], lid))
        return L

    def listar(self):
        ativo = self.ativo_id()
        out, vistos = [], set()
        for base in (self.prontos, self.pasta_usuario):
            try:
                nomes = sorted(os.listdir(base))
            except OSError:
                continue
            for n in nomes:
                lid = n[:-5]
                if not n.endswith('.json') or n.startswith('.') or lid in vistos:
                    continue
                vistos.add(lid)
                item = {'id': lid, 'nome': lid, 'descricao': '', 'embutido': base == self.prontos, 'ativo': lid == ativo,
                        'valido': True, 'erro': None, 'widgets': 0, 'grade': None, 'orientacao': 'qualquer'}
                try:
                    L = self.layout(lid)
                    item.update(nome=L['nome'], descricao=L.get('descricao', ''), widgets=len(L['widgets']),
                                grade=L['grade'], orientacao=L.get('orientacao', 'qualquer'))
                except LayoutInvalido as e:
                    item.update(valido=False, erro=str(e))
                out.append(item)
        return out

    def ativo_id(self):
        try:
            with open(self.arq_ativo, encoding='utf-8') as f:
                lid = f.read().strip()
        except OSError:
            return PADRAO
        return lid if RE_ID.match(lid) else PADRAO

    def ativo_com_erro(self):
        """(layout, motivo): se o ativo sumiu ou é inválido, volta ao `painel` e diz o porquê."""
        lid = self.ativo_id()
        try:
            return self.layout(lid), None
        except KeyError:
            motivo = 'o layout "%s" não existe mais' % lid
        except LayoutInvalido as e:
            motivo = 'o layout "%s" é inválido: %s' % (lid, e)
        return self.layout(PADRAO), motivo

    def ativo(self):
        return self.ativo_com_erro()[0]

    def ativar(self, lid):
        with self._trava:
            self.layout(lid)          # KeyError se não existe, LayoutInvalido se não passa
            os.makedirs(self.config, exist_ok=True)
            _gravar_atomico(self.arq_ativo, (lid + '\n').encode())
            self.versao += 1

    def salvar(self, layout):
        """Valida e grava a cópia do usuário. Devolve o layout como escrito.

        Decisão: salvar com o id de um layout que VEM COM O PROJETO é recusado ("escolha outro id"), igual aos
        temas; os prontos nunca mudam. A tela de edição sugere `<id>-meu` já no campo de id. Salvar com o id de
        um layout do próprio usuário substitui o arquivo dele (é assim que se edita)."""
        validar(layout)
        lid = layout['id']
        if self.pronto(lid):
            raise LayoutInvalido('id: "%s" já é um layout que vem com o projeto; escolha outro id (ex. %s-meu)'
                                 % (lid, lid[:28]))
        with self._trava:
            os.makedirs(self.pasta_usuario, exist_ok=True)
            _gravar_atomico(os.path.join(self.pasta_usuario, lid + '.json'), para_bytes(layout))
            if lid == self.ativo_id():
                self.versao += 1      # editou o ativo: as páginas abertas recarregam
        return layout

    def importar(self, dados):
        """Valida os bytes de um .json e grava em <config>/layouts/. Devolve o layout."""
        return self.salvar(carregar(bytes(dados)))

    def exportar(self, lid):
        """Os bytes do .json do layout `lid` (pronto ou do usuário)."""
        return para_bytes(self.layout(lid))

    def apagar(self, lid):
        """Apaga um layout do usuário. Os prontos não se apagam (LayoutInvalido). KeyError se não existe."""
        if self.pronto(lid):
            raise LayoutInvalido('id: "%s" vem com o projeto e não pode ser apagado' % lid)
        if not isinstance(lid, str) or not RE_ID.match(lid):
            raise KeyError(lid)
        with self._trava:
            p = os.path.join(self.pasta_usuario, lid + '.json')
            if not os.path.isfile(p):
                raise KeyError(lid)
            ativo = lid == self.ativo_id()
            os.unlink(p)
            if ativo:                 # o ativo sumiu: volta ao padrão
                try:
                    os.unlink(self.arq_ativo)
                except OSError:
                    pass
                self.versao += 1


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
