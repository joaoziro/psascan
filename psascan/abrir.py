"""Abre o painel do psascan com um clique (usado pelo ícone do menu/área de trabalho).

  python3 abrir.py            carro de verdade (scanner salvo com "cli.py porta"), http://127.0.0.1:8096
  python3 abrir.py --sim      simulador com a gravação real, http://127.0.0.1:8099 (porta separada)
  python3 abrir.py --parar    encerra o servidor do carro (e o do simulador)

Se o servidor já estiver rodando, só abre a janela de novo (não abre outro). O servidor
fica rodando mesmo se a janela for fechada: uma gravação em andamento não é perdida.
Para encerrar: clique direito no ícone → "Encerrar psascan".
"""
import argparse, json, os, shutil, signal, subprocess, sys, time, urllib.request

AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ = os.path.dirname(AQUI)
CACHE = os.path.expanduser('~/.cache/psascan')
CARRO, SIM = 8096, 8099


def no_ar(porta):
    """None se não tem servidor na porta; senão o /api/info dele."""
    try:
        with urllib.request.urlopen('http://127.0.0.1:%d/api/info' % porta, timeout=1) as r:
            return json.loads(r.read())
    except (OSError, ValueError):
        return None


def pid_arquivo(porta):
    return os.path.join(CACHE, 'web-%d.pid' % porta)


def iniciar(porta, sim):
    os.makedirs(CACHE, exist_ok=True)
    cmd = [sys.executable, os.path.join(AQUI, 'web.py'), '--http', str(porta)]
    if sim:
        cmd += ['--sim', '--sim-csv', os.path.join(RAIZ, 'registros', '20261025-163040.csv')]
    log = open(os.path.join(CACHE, 'web-%d.log' % porta), 'a')
    log.write('\n=== %s %s\n' % (time.strftime('%Y-%m-%d %H:%M:%S'), ' '.join(cmd)))
    log.flush()
    p = subprocess.Popen(cmd, cwd=RAIZ, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                         start_new_session=True)      # sobrevive ao fechar a janela
    with open(pid_arquivo(porta), 'w') as f:
        f.write(str(p.pid))
    for _ in range(50):
        if no_ar(porta) is not None:
            return True
        if p.poll() is not None:
            return False
        time.sleep(0.2)
    return no_ar(porta) is not None


def abrir_janela(url):
    # janela própria (sem barra de endereço), como um aplicativo; senão, o navegador padrão
    for nav in ('google-chrome', 'chromium', 'chromium-browser'):
        if shutil.which(nav):
            subprocess.Popen([nav, '--app=' + url, '--class=psascan'], stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, start_new_session=True)
            return
    subprocess.Popen(['xdg-open', url], start_new_session=True)


def avisar(msg):
    print(msg)
    if shutil.which('notify-send'):
        subprocess.run(['notify-send', '-i', os.path.join(AQUI, 'static', 'icone.svg'), 'psascan', msg])


def parar():
    for porta in (CARRO, SIM):
        try:
            pid = int(open(pid_arquivo(porta)).read())
            os.killpg(pid, signal.SIGINT)        # como Ctrl+C: fecha a gravação e a captura direitinho
            os.remove(pid_arquivo(porta))
            avisar('servidor da porta %d encerrado' % porta)
        except (OSError, ValueError):
            pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--sim', action='store_true', help='simulador (porta %d), não o carro' % SIM)
    ap.add_argument('--parar', action='store_true')
    o = ap.parse_args()
    if o.parar:
        return parar()
    porta = SIM if o.sim else CARRO
    info = no_ar(porta)
    if info and not o.sim and info.get('simulador'):
        avisar('Um SIMULADOR está ocupando a porta do carro (8096), aberto pelo terminal. '
               'Pare ele com Ctrl+C no terminal e clique no ícone de novo.')
        return 1
    if not info and not iniciar(porta, o.sim):
        avisar('não consegui iniciar o painel; veja %s' % os.path.join(CACHE, 'web-%d.log' % porta))
        return 1
    abrir_janela('http://127.0.0.1:%d/' % porta)


if __name__ == '__main__':
    sys.exit(main())
