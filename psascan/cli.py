"""psascan — diagnóstico de uma central (perfil padrão: Sagem SL96, Peugeot 306 1.8 16v) pelo scanner PSA COM.

  python3 cli.py dispositivos           lista os Bluetooth pareados (bluetoothctl)
  python3 cli.py porta AA:BB:CC:DD:EE:FF   salva o scanner padrão (conexão direta, sem rfcomm)
  python3 cli.py versao                 só o scanner, não fala com a central
  python3 cli.py falhas
  python3 cli.py lambda                 sonda lambda ao vivo + diagnóstico (só o bloco da lambda, mais rápido)
  python3 cli.py ao-vivo [--completo]   todos os parâmetros, contínuo (Ctrl+C para parar)
  python3 cli.py atuadores | quadros
  python3 cli.py --escrita apagar       apaga os códigos de falha (muda o estado da central)
  python3 cli.py --escrita atuador N    aciona o atuador N da lista (id ou posição)

Somente leitura por padrão: apagar e atuador exigem --escrita.
Antes de cada ação mostra todos os quadros que ela pode enviar e pede UMA
confirmação. Se a conexão cair, reconecta sozinho repetindo só a sequência de
conexão (versão, linha K, início) — inclusive com o carro andando.
Tudo da serial vai para capturas/*.jsonl; leituras ao vivo para registros/*.csv.
"""
import argparse, os, subprocess, sys, time

import conexao, dongle, perfil as _perfil, psa5, registro

CONF = os.path.expanduser('~/.config/psascan/porta')


def porta_padrao():
    try:
        return open(CONF).read().strip()
    except OSError:
        return '/dev/rfcomm0'


def main(argv=None):
    ap = argparse.ArgumentParser(description='Diagnóstico de central via PSA COM (perfil padrão: Sagem SL96)')
    ap.add_argument('--porta', default=None, help='MAC do scanner ou tty (padrão: o salvo com "porta")')
    ap.add_argument('--sim-confirmar', action='store_true', help='não pedir confirmação')
    ap.add_argument('--perfil', help='perfil da central (padrão: perfis/sagem-sl96/perfil.json)')
    ap.add_argument('--escrita', action='store_true',
                    help='habilita os comandos que mudam o estado da central (apagar falhas, atuadores)')
    ap.add_argument('--captura', help='arquivo .jsonl (padrão: capturas/<data-hora>.jsonl)')
    ap.add_argument('--sem-captura', action='store_true')
    ap.add_argument('-v', '--verboso', action='store_true', help='mostra TX/RX')
    sub = ap.add_subparsers(dest='cmd', required=True)
    for c in ('dispositivos', 'versao', 'falhas', 'apagar', 'atuadores', 'quadros', 'lambda'):
        sub.add_parser(c)
    sp = sub.add_parser('porta'); sp.add_argument('endereco')
    v = sub.add_parser('ao-vivo'); v.add_argument('--completo', action='store_true'); v.add_argument('-n', type=int, default=0)
    a = sub.add_parser('atuador'); a.add_argument('n', help='posição na lista de "atuadores" ou o id')
    a.add_argument('--perigoso', action='store_true', help='permite bomba/injetores (ainda pede para digitar PERIGO)')
    o = ap.parse_args(argv)
    try:
        b = _perfil.carregar(o.perfil)
    except _perfil.PerfilInvalido as e:
        print('perfil inválido:', e)
        return 2
    exigir_escrita = o.cmd in ('apagar', 'atuador') and not o.escrita
    if exigir_escrita:
        print('%s muda o estado da central e está desabilitado: somente leitura por padrão.\n'
              'Repita com --escrita (antes do comando), ex.: python3 cli.py --escrita %s' % (o.cmd, o.cmd))
        return 1
    atu = None
    if o.cmd == 'atuador':
        lst = b.atuadores()
        try:
            atu = lst[int(o.n)] if o.n.isdigit() else b.escrita_por_id(o.n)
        except (IndexError, KeyError):
            print('atuador desconhecido: %s (veja "atuadores")' % o.n)
            return 1
    if o.cmd == 'apagar' and not b.escrita_apagar():
        print('este perfil não tem o comando de apagar falhas')
        return 1

    if o.cmd == 'dispositivos':
        r = subprocess.run(['bluetoothctl', 'devices', 'Paired'], capture_output=True, text=True)
        print(r.stdout or r.stderr or 'nenhum dispositivo pareado')
        print('pareie com: bluetoothctl → scan on → pair <MAC> → trust <MAC>')
        return 0
    if o.cmd == 'porta':
        os.makedirs(os.path.dirname(CONF), exist_ok=True)
        open(CONF, 'w').write(o.endereco + '\n')
        print('porta padrão salva:', o.endereco)
        return 0
    if o.cmd == 'atuadores':
        for i, x in enumerate(b.atuadores()):
            nome = x['nome'] + ('  [BLOQUEADO: modo de segurança]' if x.get('perigoso') else '')
            print('%d  %-45s %-8s %s' % (i, nome, x['pedido'], x['id']))
        return 0
    if o.cmd == 'quadros':
        for ac in ['versao', 'falhas', 'apagar', 'completo'] + [e['id'] for e in b.atuadores()]:
            print('[%s]' % ac)
            for q in conexao.quadros_da_acao(b, ac):
                print('  %-24s %s' % (q['desc'], q['bytes']))
        return 0

    acao = ('completo' if o.completo else 'rapido') if o.cmd == 'ao-vivo' else o.cmd
    if o.cmd == 'atuador':
        acao = atu['id']
    print('ação: %s — quadros que podem ser enviados:' % acao)
    for q in conexao.quadros_da_acao(b, acao):
        print('  %-28s %s' % (q['desc'], q['bytes']))
    if o.cmd in ('ao-vivo', 'lambda'):
        print('  (repetidos até Ctrl+C; em queda de conexão a sequência de conexão é refeita sozinha)')
    liberar = False
    if atu and atu.get('perigoso'):
        a = atu
        if not o.perigoso:
            print('BLOQUEADO (modo de segurança): %s. O teste não para sozinho; bomba/injetores com motor'
                  ' parado podem encharcar os cilindros. Use --perigoso se tiver certeza.' % a['nome'])
            return 1
        if input('ATENÇÃO: %s. Para parar pode ser preciso desligar a chave.\nDigite %s para confirmar: '
                 % (a['nome'], psa5.FRASE_PERIGO)).strip() != psa5.FRASE_PERIGO:
            print('cancelado')
            return 1
        liberar = True
    if not o.sim_confirmar and input('enviar? [s/N] ').strip().lower() not in ('s', 'sim', 'y'):
        print('cancelado')
        return 1

    porta = o.porta or porta_padrao()
    cap = None if o.sem_captura else (o.captura or dongle.caminho_captura())
    con = conexao.Conexao(porta, b, cap, log=(lambda s: print('   ' + s)) if o.verboso else (lambda s: None),
                          escrita=o.escrita)
    if cap:
        print('captura em', cap)
    if con.cap:
        con.cap('nota', texto='cli: ' + ' '.join(argv if argv is not None else sys.argv[1:]))
    try:
        if o.cmd == 'versao':   # só o scanner: sem linha K, sem reconexão
            d = dongle.Dongle(porta, log=con.log, captura=con.cap)
            try:
                print(d.versao())
            finally:
                d.close()
        elif o.cmd == 'falhas':
            lst, bruto = con.executar(lambda e: e.ler_falhas())
            if lst is None:
                print('central sem resposta válida:', bruto.hex(' '))
            elif not lst:
                print('nenhum código de falha')
            for f in lst or []:
                print('%03X  %-50s %-35s %s' % (f['codigo'], f['nome'] + (' [nome provável]' if f['pendente'] else ''),
                                                f['tipo_nome'], 'presente' if f['presente'] else 'memorizada'))
        elif o.cmd == 'apagar':
            print('resposta:', con.executar(lambda e: e.escrever(b.escrita_apagar()['id'])).hex(' '))
        elif o.cmd == 'atuador':
            print('resposta:', con.executar(lambda e: e.escrever(atu['id'], liberar)).hex(' '))
        elif o.cmd in ('ao-vivo', 'lambda'):
            return monitorar(con, acao if o.cmd == 'ao-vivo' else 'rapido', o.cmd == 'lambda', getattr(o, 'n', 0))
    except KeyboardInterrupt:
        print('interrompido')
        return 1
    except conexao.ERROS_DE_LINK as e:
        print('sem comunicação após várias tentativas:', e)
        return 2
    finally:
        con.close()
    return 0


def barra(mv, largura=40):
    s = ['·'] * largura
    s[int(450 / 1000 * largura)] = '|'
    s[max(0, min(largura - 1, int(mv / 1000 * largura)))] = '█'
    return ''.join(s)


def monitorar(con, modo, so_lambda, n):
    p = con.perfil
    lam = p.lambda_
    csv = registro.caminho_registro()
    m = conexao.Monitor(con, modo, csv)
    print('registro em', csv)
    m.iniciar()
    vistos, ultimo_resumo = 0, time.monotonic()
    try:
        while m.rodando:
            time.sleep(0.1)
            e = m.estado()
            if e['conexao'] != 'conectado':
                print('\r[%s… reconexões: %d] %-50s' % (e['conexao'], e['reconexoes'], e['ultimo_erro'][:50]),
                      end='', flush=True)
                continue
            if m.leituras == vistos:
                continue
            vistos = m.leituras
            if so_lambda:
                val = {v['id']: v['valor'] for v in e['valores']}
                tl = val.get(lam['id']) if lam else None
                if tl:
                    mv = float(tl.split()[0])
                    print('\r%6.0f mV %s  %s  água %s  %.1f leit/s   ' %
                          (mv, barra(mv), val.get('rpm', '?'), val.get('agua', '?'), e['taxa_hz']),
                          end='', flush=True)
                if time.monotonic() - ultimo_resumo > 5:
                    ultimo_resumo = time.monotonic()
                    a = e['lambda']['analise']
                    print('\n  últimos 10 s: %s | min %s max %s média %s mV | trocas %s | %s%% do tempo rico' %
                          (a['diagnostico'], a.get('min'), a.get('max'), a.get('media'), a.get('trocas'), a.get('pct_rico')))
            else:
                print('\n--- leitura %d (%.1f/s, reconexões %d)' % (m.leituras, e['taxa_hz'], e['reconexoes']))
                for v in e['valores']:
                    print('%-45s %s' % (v['nome'], v['valor']))
            if n and m.leituras >= n:
                break
    except KeyboardInterrupt:
        pass
    finally:
        m.parar()
        print('\nregistro salvo em', csv)
        if so_lambda:
            print(registro.DICAS_LAMBDA)
    return 0


if __name__ == '__main__':
    sys.exit(main())
