#!/usr/bin/env python3
"""Run on the Debian server: secrets are read locally, never included in the delivery."""
import getpass
import platform
import os
from pathlib import Path
import secrets
from urllib.parse import quote,urlparse

root=Path(__file__).resolve().parent.parent
path=root/'.env'
if path.exists():
    raise SystemExit('.env já existe. Edite-o localmente para preservar sua chave e configuração.')
address=input('Endereço da API acessível pelo celular (ex.: http://192.168.0.10:8088): ').strip().rstrip('/')
if any(ord(c) < 32 or ord(c) == 127 or c in ("'", '"', ' ', '\t', '\r', '\n') for c in address):
    raise SystemExit('Endereço inválido.')
parsed=urlparse(address)
if parsed.scheme not in ('http','https') or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
    raise SystemExit('Endereço inválido.')
password=getpass.getpass('Senha do usuário viblio no PostgreSQL (não será exibida): ')
if not password or any(c in password for c in ('\r', '\n', '\x00')):
    raise SystemExit('A senha é necessária e não pode conter quebras de linha.')
# Quote values for Compose interpolation; literal dollar signs are escaped.
def env(value): return "'"+value.replace("'","\\'").replace('$','$$')+"'"
content='DATABASE_URL='+env('postgresql://viblio:'+quote(password,safe='')+'@postgres:5432/viblio')+'\n'
content+='OFFLINE_WHEELS='+('1' if platform.machine() in ('x86_64','AMD64') and any((root/'wheelhouse').glob('*.whl')) else '0')+'\n'
content+='VIBLIO_API_TOKEN='+env(secrets.token_hex(32))+'\nPUBLIC_BASE_URL='+env(address)+'\n'
fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as output: output.write(content)
print('Configuração salva em .env com permissão privada. Copie apenas VIBLIO_API_TOKEN para o APK, no seu dispositivo. Não envie a chave nem a senha no chat.')
