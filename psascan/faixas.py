"""Faixas de referência das métricas: normal / atenção / fora.

As regras padrão e as condições vêm do perfil da central (perfis/<id>/perfil.json). Cada métrica
tem uma lista de regras; vale a primeira cuja condição bate com o momento. Fora de qualquer
condição o estado é 'neutro' (sem cor).

Regra: {'quando': condição, 'normal': [mín, máx], 'atencao': [mín, máx],
        'abaixo': texto, 'acima': texto, 'nota': texto}
  dentro de 'normal' -> normal; dentro de 'atencao' -> atenção; senão -> fora.
  bits: {'quando': ..., 'normal_valor': 0|1, 'msg': texto} (diferente -> atenção).

Ajustes do usuário: ~/.config/psascan/faixas.json = {"ordem": [regras]} substitui
as regras daquela métrica.
"""
import json, os

import perfil as _perfil

AJUSTES = os.path.expanduser('~/.config/psascan/faixas.json')

# A lambda não tem faixa de valor: o estado vem da análise das trocas rico↔pobre,
# e só vale com o motor quente (condição "quente" do perfil, se ele a definir).
ESTADO_LAMBDA = {'OSCILANDO': 'normal', 'VARIA': 'atencao', 'PARADA': 'fora'}
COND_LAMBDA = 'quente'


def contexto(perfil, vals):
    """vals: {ordem: número}. Devolve o conjunto de condições verdadeiras agora (ver perfil.contexto)."""
    return _perfil.contexto(perfil, vals)


class Faixas:
    def __init__(self, perfil, ajustes=AJUSTES):
        self.perfil = perfil
        self.padrao = {m['ordem']: m['faixas'] for m in perfil.metricas if m.get('faixas')}
        self.lambda_ = perfil.lambda_['ordem'] if perfil.lambda_ else None
        self.caminho = ajustes
        self.ajustes = {}
        try:
            with open(ajustes, encoding='utf-8') as f:
                self.ajustes = {int(k): v for k, v in json.load(f).items()}
        except (OSError, ValueError):
            pass

    def regras(self, ordem):
        return self.ajustes.get(ordem, self.padrao.get(ordem, []))

    def todas(self):
        """{ordem: {'regras': [...], 'ajustado': bool}} para a tela."""
        ords = set(self.padrao) | set(self.ajustes)
        return {o: {'regras': self.regras(o), 'ajustado': o in self.ajustes} for o in sorted(ords)}

    def ajustar(self, ordem, regras):
        """regras=None volta ao padrão."""
        for r in regras or []:
            if r.get('quando') not in self.perfil.condicoes and r.get('quando') != 'sempre':
                raise ValueError('condição desconhecida: %r' % r.get('quando'))
            for k in ('normal', 'atencao'):
                if k in r and not (len(r[k]) == 2 and float(r[k][0]) <= float(r[k][1])):
                    raise ValueError('faixa inválida em %s: %r' % (k, r[k]))
        if regras is None:
            self.ajustes.pop(ordem, None)
        else:
            self.ajustes[ordem] = regras
        os.makedirs(os.path.dirname(self.caminho), exist_ok=True)
        tmp = self.caminho + '.tmp'
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump({str(k): v for k, v in self.ajustes.items()}, f, ensure_ascii=False, indent=1)
        os.replace(tmp, self.caminho)

    def avaliar(self, ordem, valor, ctx, lambda_diag=None):
        """-> {'estado': normal|atencao|fora|neutro, 'msg': str, 'regra': regra ou None,
               'lado': 'acima'|'abaixo'|None (de que lado da faixa normal o valor saiu)}"""
        if ordem == self.lambda_:
            if COND_LAMBDA in self.perfil.condicoes and COND_LAMBDA not in ctx:
                return {'estado': 'neutro', 'msg': 'motor frio ou parado: malha aberta', 'regra': None, 'lado': None}
            for k, e in ESTADO_LAMBDA.items():
                if (lambda_diag or '').startswith(k):
                    return {'estado': e, 'msg': lambda_diag, 'regra': None, 'lado': None}
            return {'estado': 'neutro', 'msg': lambda_diag or '', 'regra': None, 'lado': None}
        if valor is None:
            return {'estado': 'neutro', 'msg': '', 'regra': None, 'lado': None}
        for r in self.regras(ordem):
            if r['quando'] not in ctx:
                continue
            if 'normal_valor' in r:
                ok = valor == r['normal_valor']
                return {'estado': 'normal' if ok else 'atencao', 'msg': '' if ok else r.get('msg', ''), 'regra': r,
                        'lado': None}
            lo, hi = r['normal']
            if lo <= valor <= hi:
                return {'estado': 'normal', 'msg': '', 'regra': r, 'lado': None}
            lado = 'abaixo' if valor < lo else 'acima'
            msg = r.get(lado, lado + ' da faixa')
            a = r.get('atencao')
            if a and a[0] <= valor <= a[1]:
                return {'estado': 'atencao', 'msg': msg, 'regra': r, 'lado': lado}
            return {'estado': 'fora', 'msg': msg, 'regra': r, 'lado': lado}
        return {'estado': 'neutro', 'msg': '', 'regra': None, 'lado': None}
