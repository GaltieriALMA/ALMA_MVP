import json
from pathlib import Path
from datetime import datetime, timezone

ARCHIVO = Path(__file__).with_name('experiencias.json')

def guardar_experiencia(problema, intento, resultado, verificado=False):
    experiencias = json.loads(ARCHIVO.read_text(encoding='utf-8')) if ARCHIVO.exists() else []
    experiencia = {
                'fecha': datetime.now(timezone.utc).isoformat(),
                        'problema': problema,
                                'intento': intento,
                                        'resultado': resultado,
                                                'verificado': verificado
                                                    }
    experiencias.append(experiencia)
    ARCHIVO.write_text(json.dumps(experiencias, ensure_ascii=False, indent=2), encoding='utf-8')
    return experiencia
                                                                
def normalizar(texto):
    import unicodedata
    return ''.join(c for c in unicodedata.normalize('NFD', texto.lower()) if unicodedata.category(c) != 'Mn')

def consultar_experiencia(tema):
    experiencias = json.loads(ARCHIVO.read_text(encoding='utf-8')) if ARCHIVO.exists() else []
    for experiencia in reversed(experiencias):
        if normalizar(tema) in normalizar(experiencia['problema']) and experiencia['verificado']:
            return experiencia['intento']
    return 'Todavía no tengo una solución comprobada'
