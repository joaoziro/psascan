"""Gera os dados do app a partir da versão Python (uma fonte só para as duas):

  app/src/main/assets/perfil.json      cópia, sem transformar, de perfis/sagem-sl96/perfil.json
  app/src/main/assets/temas/<id>/      cópia, sem transformar, de cada temas/<id>/ (tema.json e arquivos/)
  app/src/main/assets/layouts/         cópia, sem transformar, de cada layouts/<id>.json
  app/src/main/assets/widgets/catalogo.json  cópia, sem transformar, de psascan/static/widgets/catalogo.json
  app/src/test/resources/vetores.json  quadros, conversões, faixas e lambda calculados pelo Python
                                       (psascan/), para os testes do Kotlin conferirem o mesmo resultado

Roda sozinho antes de cada build (tarefa gerarDados do Gradle); à mão:  python3 android/gerar_dados.py
"""
import json, os, shutil, sys

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(AQUI, '..', 'psascan'))
import dongle, faixas, layouts, perfil as P, psa5, registro, sim, temas  # noqa: E402

PERFIL_JSON = os.path.join(AQUI, '..', 'perfis', 'sagem-sl96', 'perfil.json')
TEMAS = os.path.join(AQUI, '..', 'temas')
LAYOUTS = os.path.join(AQUI, '..', 'layouts')


def escrever(caminho, dados):
    os.makedirs(os.path.dirname(caminho), exist_ok=True)
    novo = json.dumps(dados, ensure_ascii=False, indent=1, sort_keys=True) + '\n'
    try:
        with open(caminho, encoding='utf-8') as f:
            if f.read() == novo:
                return                    # não mexe no arquivo: o Gradle não refaz o build à toa
    except OSError:
        pass
    with open(caminho, 'w', encoding='utf-8') as f:
        f.write(novo)


def copiar_perfil(destino):
    """Copia o perfil sem mexer no conteúdo; não toca no arquivo se nada mudou (o Gradle não refaz o build à toa)."""
    with open(PERFIL_JSON, 'rb') as f:
        novo = f.read()
    try:
        with open(destino, 'rb') as f:
            if f.read() == novo:
                return
    except OSError:
        pass
    os.makedirs(os.path.dirname(destino), exist_ok=True)
    with open(destino, 'wb') as f:
        f.write(novo)


def copiar_temas(destino):
    """Copia cada temas/<id>/ (tema.json e arquivos/) sem transformar; só escreve o que mudou."""
    for tid in sorted(os.listdir(TEMAS)):
        pasta = os.path.join(TEMAS, tid)
        if not os.path.isfile(os.path.join(pasta, 'tema.json')):
            continue
        for raiz, _, nomes in os.walk(pasta):
            for n in sorted(nomes):
                orig = os.path.join(raiz, n)
                dest = os.path.join(destino, tid, os.path.relpath(orig, pasta))
                with open(orig, 'rb') as f:
                    novo = f.read()
                try:
                    with open(dest, 'rb') as f:
                        if f.read() == novo:
                            continue
                except OSError:
                    pass
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with open(dest, 'wb') as f:
                    f.write(novo)


def copiar_arquivo(orig, dest):
    """Copia um arquivo sem transformar; só escreve se mudou."""
    with open(orig, 'rb') as f:
        novo = f.read()
    try:
        with open(dest, 'rb') as f:
            if f.read() == novo:
                return
    except OSError:
        pass
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, 'wb') as f:
        f.write(novo)


def copiar_layouts(destino):
    """Copia cada layouts/<id>.json sem transformar."""
    for n in sorted(os.listdir(LAYOUTS)):
        if n.endswith('.json'):
            copiar_arquivo(os.path.join(LAYOUTS, n), os.path.join(destino, n))


def vetores_layouts():
    """Casos de validação de layout (uma regra de docs/LAYOUT.md por caso) para o Kotlin conferir."""
    def w(**extra):
        d = {'id': 'a', 'tipo': 'numero', 'metrica': 'rpm', 'x': 0, 'y': 0, 'largura': 2, 'altura': 2}
        d.update(extra)
        return d
    def lay(*ws, **extra):
        d = {'formato': 1, 'id': 'teste', 'nome': 'Teste', 'grade': {'colunas': 12, 'linhas': 6}, 'widgets': list(ws)}
        d.update(extra)
        return d
    bom = lay(w(), w(id='b', tipo='mostrador', x=2, largura=4, altura=4,
                     opcoes={'escala': {'min': 0, 'max': 7000, 'maior': 1000}, 'centro': {'conteudo': 'rolos'},
                             'arco_inferior': {'metrica': 'agua', 'min': 50, 'max': 130}}),
              w(id='c', tipo='luz', metrica=None, x=6, opcoes={'fonte': 'falhas'}))
    del bom['widgets'][2]['metrica']
    sem_metrica = w()
    del sem_metrica['metrica']
    casos = [
        ('layout válido', bom, ''),
        ('sem widgets', lay(), ''),
        ('formato diferente de 1', lay(formato=2), 'formato'),
        ('id ruim', lay(id='Ruim_ID'), 'id'),
        ('sem nome', {k: v for k, v in lay().items() if k != 'nome'}, 'nome'),
        ('campo desconhecido', lay(extra=1), 'extra'),
        ('orientação fora da lista', lay(orientacao='diagonal'), 'orientacao'),
        ('colunas fora da faixa', lay(grade={'colunas': 3, 'linhas': 6}), 'grade.colunas'),
        ('linhas fora da faixa', lay(grade={'colunas': 12, 'linhas': 13}), 'grade.linhas'),
        ('mais de 40 widgets', lay(*[w(id='w%d' % i, x=0, y=0) for i in range(41)]), 'widgets'),
        ('id de widget repetido', lay(w(), w(x=4)), 'repetido'),
        ('tipo desconhecido', lay(w(tipo='radar')), 'tipo'),
        ('widget sem métrica', lay(sem_metrica), 'metrica'),
        ('métrica com id inválido', lay(w(metrica='RPM!')), 'metrica'),
        ('falhas não usa métrica', lay(w(tipo='falhas', largura=3, altura=2)), 'metrica'),
        ('largura abaixo do mínimo', lay(w(tipo='mostrador', largura=1)), 'largura'),
        ('altura acima do máximo', lay(w(altura=5)), 'altura'),
        ('sai da grade pela direita', lay(w(x=11)), 'widgets[0].x'),
        ('sai da grade por baixo', lay(w(y=5)), 'widgets[0].y'),
        ('sobreposição', lay(w(), w(id='b', x=1, y=1)), 'sobrepõe'),
        ('opção desconhecida', lay(w(opcoes={'cor': 'azul'})), 'opcoes.cor'),
        ('opção bool com texto', lay(w(opcoes={'mostrar_unidade': 'sim'})), 'mostrar_unidade'),
        ('opção inteira fora da faixa', lay(w(opcoes={'decimais': 9})), 'decimais'),
        ('opção fora da lista', lay(w(tipo='barra', opcoes={'orientacao': 'diagonal'})), 'orientacao'),
        ('opção de objeto sem campo obrigatório', lay(w(tipo='mostrador', opcoes={'arco_inferior': {'metrica': 'agua'}})), 'arco_inferior'),
        ('opção de objeto com campo desconhecido', lay(w(tipo='mostrador', opcoes={'centro': {'fonte': 1}})), 'centro.fonte'),
        ('opção de métrica com id inválido', lay(w(tipo='mostrador', opcoes={'centro': {'conteudo': 'metrica', 'metrica': 'A B'}})), 'centro.metrica'),
        ('número maior que zero', lay(w(tipo='mostrador', opcoes={'escala': {'maior': 0}})), 'escala.maior'),
        ('texto longo demais', lay(w(tipo='luz', largura=1, altura=1, metrica='luz_injecao', opcoes={'rotulo': 'x' * 21})), 'rotulo'),
        ('título longo demais', lay(w(titulo='x' * 41)), 'titulo'),
    ]
    out = []
    for nome, L, contem in casos:
        texto = json.dumps(L, ensure_ascii=False)
        try:
            layouts.carregar(texto.encode('utf-8'))
            motivo = ''
        except layouts.LayoutInvalido as e:
            motivo = str(e)
        assert (motivo == '') == (contem == '') and contem in motivo, (nome, motivo)
        out.append({'nome': nome, 'json': texto, 'valido': motivo == '', 'motivo_contem': contem})
    return {'casos': out}


def vetores_temas():
    """Casos de validação de tema para o Kotlin conferir (mesmas regras do temas.py) e o Padrão completado."""
    base = {'formato': 1, 'id': 'teste', 'nome': 'Teste'}
    def com(**extra):
        t = dict(base)
        t.update(extra)
        return t
    parcial = com(interface={'escuro': {'accent': '#ff0000'}}, painel={'ponteiro': {'estilo': 'barra'}})
    casos = [
        ('padrao', open(temas.PADRAO, encoding='utf-8').read(), ''),
        ('parcial', json.dumps(parcial), ''),
        ('cor ruim', json.dumps(com(interface={'escuro': {'accent': 'vermelho'}})), 'interface.escuro.accent'),
        ('id ruim', json.dumps(dict(base, id='Ruim_ID')), 'id'),
        ('campo desconhecido', json.dumps(com(extra=1)), 'extra'),
        ('enum fora da lista', json.dumps(com(painel={'ponteiro': {'estilo': 'laser'}})), 'painel.ponteiro.estilo'),
        ('numero fora da faixa', json.dumps(com(painel={'espessuras': {'aro': 99}})), 'painel.espessuras.aro'),
        ('contraste baixo', json.dumps(com(painel={'cores': {'texto': '#010101'}})), 'contraste'),
    ]
    out = []
    for nome, texto, contem in casos:
        try:
            temas.carregar(texto.encode('utf-8'))
            motivo = ''
        except temas.TemaInvalido as e:
            motivo = str(e)
        assert (motivo == '') == (contem == '') and contem in motivo, (nome, motivo)
        out.append({'nome': nome, 'json': texto, 'valido': motivo == '', 'motivo_contem': contem})
    return {'casos': out, 'completado': temas.completar(parcial)}


def vetores(p):
    hx = lambda x: bytes(x).hex()
    c = p.conexao
    ped = lambda i: dict(p.blocos)[i]
    quadros = [{'payload': hx(x), 'quadro': hx(dongle.frame(x))} for x in (
        [dongle.CMD_VERSAO], c['pino'], c['linha'], c['destravar'], c['acordar'],
        psa5.para_dongle(c['inicio']), psa5.para_dongle(c['manter']),
        psa5.para_dongle(P.hex_bytes(p.leitura['falhas']['pedido'], 'pedido')),
        psa5.para_dongle(P.hex_bytes(p.escrita_apagar()['pedido'], 'pedido')),
        psa5.para_dongle(ped(0)), psa5.para_dongle(ped(1)), bytes([1]) + bytes(299))]
    cen = sim.Cenario(p, 'estrada')
    blocos = []
    for t in (5, 25, 50, 85, 110):
        raws = cen.raws(t)
        for bl in (0, 1):
            d = P.montar_bloco(p, bl, raws)
            vals = {}
            for m in p.metricas:
                if m['bloco'] != bl:
                    continue
                raw, num, txt = P.converter(p, m, d)
                vals[str(m['ordem'])] = {'raw': raw, 'numero': num, 'texto': txt}
            blocos.append({'bloco': bl, 'dados': hx(d), 'valores': vals})
    falhas = []
    for lst in (sim.FALHAS_TESTE, sim.FALHAS_DO_CARRO, ()):
        d = b'\x73' + b''.join(((cod << 3) | t | (0 if pr else 0x2000)).to_bytes(2, 'big') for cod, t, pr in lst)
        q = bytes([0xC1 + len(d)]) + d
        resp = q + bytes([(0xFF - sum(q)) & 0xFF])
        falhas.append({'resposta': hx(resp), 'falhas': [
            {'codigo': f['codigo'], 'tipo': f['tipo'], 'presente': f['presente']}
            for f in psa5.decodificar_falhas(psa5.desquadro(resp))]})
    # faixas: estado de cada métrica em momentos do cenário estrada
    fx = faixas.Faixas(p, ajustes='/nao/existe')
    olam = p.lambda_['ordem']
    estados = []
    for t in (5, 25, 50, 85, 110, 200, 300):
        vals = {o: P.numero(p, p.metrica(o), r) for o, r in cen.raws(t).items()}
        ctx = faixas.contexto(p, vals)
        estados.append({'valores': {str(o): v for o, v in vals.items()}, 'contexto': sorted(ctx),
                        'estados': {str(o): fx.avaliar(o, v, ctx)['estado'] for o, v in vals.items() if o != olam},
                        'msgs': {str(o): fx.avaliar(o, v, ctx)['msg'] for o, v in vals.items() if o != olam},
                        'lados': {str(o): fx.avaliar(o, v, ctx)['lado'] or '' for o, v in vals.items() if o != olam}})
    # lambda: séries com veredito conhecido
    import math
    lamb = []
    for nome, f in (('oscila', lambda t: 475 + 375 * math.copysign(1, math.sin(2 * math.pi * t))),
                    ('rico', lambda t: 840 + 5 * math.sin(t)), ('pobre', lambda t: 120.0),
                    ('meio', lambda t: 450 + 20 * math.sin(t)), ('devagar', lambda t: 100 if t < 6 else 850)):
        a = registro.AnaliseLambda()
        serie = [(k * 0.3, f(k * 0.3)) for k in range(40)]
        for t, v in serie:
            a.add(t, v)
        lamb.append({'nome': nome, 'serie': serie, 'resumo': a.resumo()})
    return {'faixas': estados, 'lambda': lamb, 'crc_123456789': hx(dongle.crc16_x25(b'123456789')), 'quadros': quadros, 'blocos': blocos,
            'falhas': falhas, 'temas': vetores_temas(), 'layouts': vetores_layouts()}


if __name__ == '__main__':
    p = P.carregar(PERFIL_JSON)
    copiar_perfil(os.path.join(AQUI, 'app', 'src', 'main', 'assets', 'perfil.json'))
    copiar_temas(os.path.join(AQUI, 'app', 'src', 'main', 'assets', 'temas'))
    copiar_layouts(os.path.join(AQUI, 'app', 'src', 'main', 'assets', 'layouts'))
    copiar_arquivo(layouts.CATALOGO, os.path.join(AQUI, 'app', 'src', 'main', 'assets', 'widgets', 'catalogo.json'))
    escrever(os.path.join(AQUI, 'app', 'src', 'test', 'resources', 'vetores.json'), vetores(p))
