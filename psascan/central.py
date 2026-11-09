"""Registro das famílias de protocolo: o perfil diz a família, aqui se acha o código dela."""
import psa5

FAMILIAS = {'psa5': psa5.Central}


def classe(perfil):
    try:
        return FAMILIAS[perfil.familia]
    except KeyError:
        raise ValueError('família de protocolo sem implementação: %r' % perfil.familia)


def abrir(dongle, perfil, **kw):
    """Central do perfil sobre o scanner `dongle`. kw: confirmar=, escrita=."""
    return classe(perfil)(dongle, perfil, **kw)


def modulo(perfil):
    """Módulo da família (quadros de pré-visualização)."""
    classe(perfil)
    return {'psa5': psa5}[perfil.familia]
