#!/usr/bin/env python3
"""Change only PUBLIC_BASE_URL; keep a private backup outside the Docker build context."""
import argparse
import ipaddress
import os
from pathlib import Path
import re
import tempfile
import uuid
from urllib.parse import urlsplit


def validate_url(value):
    value=value.strip().rstrip('/')
    try:
        parsed=urlsplit(value)
        valid=(parsed.scheme=='https' and parsed.hostname and '.' in parsed.hostname and
               parsed.username is None and parsed.password is None and not parsed.query and not parsed.fragment and
               not parsed.path and parsed.port in (None,443) and not re.search(r'\s',value))
    except ValueError:
        valid=False
    if not valid:raise ValueError('Use a origem HTTPS pública, sem caminho, parâmetros ou credenciais.')
    host=parsed.hostname.lower()
    labels=host.split('.')
    if len(host)>253 or labels[-1].isdigit() or not all(re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?',label) for label in labels):
        raise ValueError('Use um nome DNS válido, sem variáveis ou caracteres especiais.')
    if host.endswith(('.local','.localhost','.ts.net')):raise ValueError('Use o domínio público do Viblio, sem depender do Tailscale.')
    try:ipaddress.ip_address(host)
    except ValueError:pass
    else:raise ValueError('Use um nome de domínio público com HTTPS, não um IP.')
    return value


def update(api_dir,backup_dir,url):
    url=validate_url(url)
    api_dir=Path(api_dir).resolve();backup_dir=Path(backup_dir).resolve()
    if backup_dir==api_dir or api_dir in backup_dir.parents:raise ValueError('O backup precisa ficar fora da pasta da API.')
    env=api_dir/'.env'
    if env.is_symlink() or not env.is_file():raise ValueError('Não encontrei um .env regular na pasta da API.')
    if env.stat().st_uid!=os.geteuid():raise ValueError('Execute como o dono do .env, sem trocar seu proprietário.')
    if env.stat().st_size>65536:raise ValueError('Arquivo .env maior que o esperado; não foi alterado.')
    original=env.read_bytes();lines=original.decode('utf-8').splitlines(keepends=True)
    matches=[i for i,line in enumerate(lines) if re.match(r'^\s*(?:export\s+)?PUBLIC_BASE_URL\s*=',line)]
    if len(matches)!=1:raise ValueError('O .env precisa conter exatamente uma linha PUBLIC_BASE_URL; nada foi alterado.')
    index=matches[0];ending='\r\n' if lines[index].endswith('\r\n') else '\n'
    lines[index]='PUBLIC_BASE_URL='+url+ending
    updated=''.join(lines).encode('utf-8')
    if updated==original:return None
    if backup_dir.exists():
        if not backup_dir.is_dir() or backup_dir.stat().st_uid!=os.geteuid() or backup_dir.stat().st_mode&0o077:
            raise ValueError('Escolha uma pasta de backup privada, sua, com permissão 700.')
    else:backup_dir.mkdir(parents=True,mode=0o700)
    backup=backup_dir/('env.before-public-'+uuid.uuid4().hex)
    fd=os.open(backup,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'wb') as out:out.write(original);out.flush();os.fsync(out.fileno())
    fd,temporary=tempfile.mkstemp(prefix='.public-url-',dir=api_dir)
    try:
        with os.fdopen(fd,'wb') as out:
            os.fchmod(out.fileno(),0o600);out.write(updated);out.flush();os.fsync(out.fileno())
        os.replace(temporary,env)
    finally:
        if os.path.exists(temporary):os.unlink(temporary)
    return backup


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api-dir',default='/opt/viblio/api')
    parser.add_argument('--backup-dir',required=True)
    parser.add_argument('--url',required=True)
    args=parser.parse_args()
    try:backup=update(args.api_dir,args.backup_dir,args.url)
    except (ValueError,OSError,UnicodeError) as error:parser.exit(1,f'Não foi possível alterar a configuração: {error}\n')
    if backup:print(f'PUBLIC_BASE_URL atualizado. Cópia anterior protegida em {backup}')
    else:print('PUBLIC_BASE_URL já está configurado com esse endereço.')

if __name__=='__main__':main()
