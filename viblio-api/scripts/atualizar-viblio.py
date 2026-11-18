#!/usr/bin/env python3
"""Atualizador da API existente. Python 3 padrão; não instala ou atualiza APKs."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid
import zipfile
from dataclasses import dataclass

CHECKSUMS = 'https://raw.githubusercontent.com/Valtecir-C-Heckert/Viblio/main/downloads/SHA256SUMS'
PACKAGE = re.compile(r'viblio-api-(\d+\.\d+\.\d+)\.zip')
MIGRATION = re.compile(r'\d{3,}_[a-z0-9_]+\.sql')
MAX_ZIP = 256 * 1024**2
MAX_EXPANDED = 512 * 1024**2

class UpdateError(Exception):
    pass


def version(value):
    if not re.fullmatch(r'\d+\.\d+\.\d+', value):
        raise UpdateError('Use uma versão como 0.2.2.')
    return tuple(map(int, value.split('.')))


def legacy_floor(api):
    """Legacy ZIPs shipped update guides before installation markers existed."""
    known = []
    for path in api.glob('UPDATE_*.md'):
        match = re.fullmatch(r'UPDATE_(\d+)_(\d+)_(\d+)\.md', path.name)
        if match:
            known.append(tuple(map(int, match.groups())))
    return max(known) if known else None


def sha(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024**2), b''):
            result.update(block)
    return result.hexdigest()


def checksum(text, name):
    matches = []
    for line in text.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].lstrip('*') == name:
            matches.append(parts[0].lower())
    if len(matches) != 1 or not re.fullmatch('[a-f0-9]{64}', matches[0]):
        raise UpdateError('Checksum único não encontrado para este pacote. Não foi instalado.')
    return matches[0]


def unpack(package, destination):
    """Never extract over the installation or allow links/credentials/path traversal."""
    if package.stat().st_size > MAX_ZIP:
        raise UpdateError('ZIP maior que 256 MiB.')
    allowed_dirs = {'app', 'migrations', 'scripts', 'tests', 'wheelhouse'}
    allowed_files = {'.dockerignore', '.gitignore', 'Dockerfile', 'compose.api.yaml',
                     'requirements.in', 'requirements.lock'}
    with zipfile.ZipFile(package) as archive:
        entries = archive.infolist()
        if len(entries) > 5000 or sum(i.file_size for i in entries) > MAX_EXPANDED:
            raise UpdateError('ZIP acima do limite de arquivos/tamanho expandido.')
        seen = set()
        for item in entries:
            name = item.filename
            if item.flag_bits & 1 or item.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                raise UpdateError('ZIP criptografado ou compressão não suportada.')
            path = PurePosixPath(name)
            mode = item.external_attr >> 16
            if (not name or name.startswith('/') or '\\' in name or
                    any(p in ('..', '.') for p in name.rstrip('/').split('/')) or
                    any(not p for p in name.rstrip('/').split('/')) or
                    stat.S_ISLNK(mode) or (stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)) or
                    any(p.startswith('.env') for p in path.parts)):
                raise UpdateError('ZIP contém caminho, tipo de arquivo ou configuração não permitidos.')
            if path.as_posix() in seen:
                raise UpdateError('ZIP contém entradas duplicadas.')
            seen.add(path.as_posix())
            if path.parts[0] not in allowed_dirs and not (len(path.parts) == 1 and
                    (name in allowed_files or name.endswith('.md'))):
                raise UpdateError('ZIP contém arquivo fora da estrutura da API.')
            if path.parts[0] == 'migrations' and not item.is_dir() and (
                    len(path.parts) != 2 or not MIGRATION.fullmatch(path.name)):
                raise UpdateError('Nome de migração não reconhecido.')
        required = {'app/main.py', 'Dockerfile', 'compose.api.yaml', 'requirements.lock'}
        if not required <= {i.filename for i in entries if not i.is_dir()}:
            raise UpdateError('ZIP não tem a estrutura completa da API.')
        # ZipFile.open validates each CRC while copying; set permissions ourselves.
        for item in entries:
            target = destination / item.filename
            if item.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(item) as source, target.open('xb') as output:
                    shutil.copyfileobj(source, output)
                target.chmod(0o700 if item.filename.startswith('scripts/') else 0o600)
    return sorted((destination / 'migrations').glob('*.sql'), key=lambda p: (int(p.name.split('_')[0]), p.name))


@dataclass
class Config:
    api: Path = Path('/opt/viblio/api')
    backups: Path = Path('/opt/viblio/backups')
    nextcloud: Path = Path('/DATA/AppData/nextcloud/var/www/html/data/valtecir/files')
    project: str = 'viblio-catalog-api'
    postgres: str = 'viblio-postgres'
    database: str = 'viblio'
    db_user: str = 'viblio'
    health_seconds: int = 180


class Updater:
    def __init__(self, config):
        self.c = config
        self.log = None
        self.backup = None
        self.stopped = False
        self.promoted = False
        self.old_image = None
        self.changed = []
        self.previous = None

    def run(self, args, *, data=None, output=None):
        result = subprocess.run([str(a) for a in args], input=data,
                                stdout=output if output is not None else subprocess.PIPE,
                                stderr=subprocess.PIPE)
        if self.log:
            if output is None and result.stdout:
                self.log.write(result.stdout)
            self.log.write(result.stderr)
            self.log.flush()
        if result.returncode:
            raise UpdateError(f'Falhou: {args[0]}. Consulte o log protegido do backup, se criado.')
        return result.stdout.decode().strip() if output is None else ''

    def compose(self, *args, directory=None, override=None):
        directory = directory or self.c.api
        command = ['docker', 'compose', '-p', self.c.project, '--project-directory', directory,
                   '--env-file', self.c.api / '.env', '-f', directory / 'compose.api.yaml']
        if override:
            command += ['-f', override]
        return self.run(command + list(args))

    def sql(self, text):
        return self.run(['docker', 'exec', '-i', self.c.postgres, 'psql', '-X',
                         '-v', 'ON_ERROR_STOP=1', '-U', self.c.db_user, '-d', self.c.database,
                         '-At'], data=text.encode())

    def discover(self, wanted=None):
        try:
            # find -print0 handles filenames with spaces; only this user's files.
            paths = subprocess.run(['find', str(self.c.nextcloud), '-type', 'f', '-name',
                                    'viblio-api-*.zip', '-print0'], capture_output=True, check=True).stdout
        except subprocess.CalledProcessError:
            paths = subprocess.run(['sudo', 'find', str(self.c.nextcloud), '-type', 'f', '-name',
                                    'viblio-api-*.zip', '-print0'], stdout=subprocess.PIPE, check=True).stdout
        found = []
        for raw in paths.split(b'\0'):
            if not raw:
                continue
            path = Path(os.fsdecode(raw))
            match = PACKAGE.fullmatch(path.name)
            if match and (wanted is None or match[1] == wanted):
                found.append((version(match[1]), path))
        if not found:
            raise UpdateError('Pacote não encontrado no seu Nextcloud. Envie viblio-api-VERSÃO.zip primeiro.')
        latest = max(v for v, _ in found)
        choices = [p for v, p in found if v == latest]
        if len(choices) != 1:
            raise UpdateError('Há cópias da mesma versão em pastas diferentes. Use --zip CAMINHO.')
        return choices[0]

    def copy_package(self, source, target):
        def bounded_copy(src, dst):
            size = 0
            while True:
                block = src.read(1024**2)
                if not block:
                    break
                size += len(block)
                if size > MAX_ZIP:
                    raise UpdateError('ZIP maior que 256 MiB.')
                dst.write(block)
        try:
            with source.open('rb') as src, target.open('xb') as dst:
                bounded_copy(src, dst)
        except PermissionError:
            target.unlink(missing_ok=True)
            with target.open('xb') as dst:
                process = subprocess.Popen(['sudo', 'cat', '--', str(source)], stdout=subprocess.PIPE)
                try:
                    bounded_copy(process.stdout, dst)
                    if process.wait():
                        raise UpdateError('Não foi possível copiar o pacote do Nextcloud.')
                finally:
                    process.stdout.close()
                    if process.poll() is None:
                        process.kill()
                    process.wait()
        target.chmod(0o600)

    def check_space(self, container):
        db_size = int(self.sql('SELECT pg_database_size(current_database());'))
        covers = int(self.run(['docker', 'exec', container, 'du', '-sk', '/data/covers']).split()[0]) * 1024
        source_size = 0
        for directory, dirs, files in os.walk(self.c.api, followlinks=False):
            dirs[:] = [d for d in dirs if d not in ('.venv', '.git', '__pycache__', '.pytest_cache')
                       and not (Path(directory) / d).is_symlink()]
            for name in files:
                path = Path(directory) / name
                if not path.is_symlink() and not name.endswith('.dump'):
                    source_size += path.stat().st_size
        needed = 2 * (db_size + covers + source_size) + 512 * 1024**2
        for location in (self.c.api.parent, self.c.backups if self.c.backups.exists() else self.c.backups.parent):
            if shutil.disk_usage(location).free < needed:
                raise UpdateError(f'Espaço insuficiente para backup e atualização: reserve ao menos {needed // 1024**2} MiB livres.')

    def ledger(self):
        if self.sql("SELECT to_regclass('public.viblio_schema_migrations') IS NOT NULL;") != 't':
            return {}
        rows = self.sql('SELECT name,sha256 FROM public.viblio_schema_migrations ORDER BY name;')
        return dict(row.split('|') for row in rows.splitlines() if row)

    def pending(self, files):
        applied = self.ledger()
        incoming = {p.name: sha(p) for p in files}
        for name, digest in applied.items():
            if incoming.get(name) != digest:
                raise UpdateError('Migração já aplicada foi alterada ou removida. Atualização interrompida: ' + name)
        return [p for p in files if p.name not in applied]

    def identity(self, container):
        return self.run(['docker', 'exec', container, 'cat', '/data/covers/catalog-id'])

    def wait_healthy(self):
        deadline = time.monotonic() + self.c.health_seconds
        while time.monotonic() < deadline:
            container = self.compose('ps', '-q', 'viblio-api')
            if container:
                state = self.run(['docker', 'inspect', '--format', '{{json .State}}', container])
                state = json.loads(state)
                if state.get('Running') and state.get('Health', {}).get('Status') == 'healthy':
                    return container
                if not state.get('Running'):
                    break
            time.sleep(3)
        raise UpdateError('A API não ficou healthy dentro do prazo.')

    def promote(self, staged):
        self.previous = staged.parent / 'previous'
        self.previous.mkdir()
        for source in sorted(staged.iterdir()):
            target = self.c.api / source.name
            if target.is_symlink():
                raise UpdateError('Destino é um link simbólico: ' + target.name)
            existed = target.exists()
            if existed:
                target.rename(self.previous / source.name)
            self.changed.append((source.name, existed))
            source.rename(target)
        self.promoted = True

    def rollback(self):
        if not self.stopped:
            return
        print('Tentando reativar o código e a imagem anteriores. O banco não será restaurado automaticamente.')
        if self.changed:
            for name, existed in reversed(self.changed):
                target = self.c.api / name
                if target.is_dir():
                    shutil.rmtree(target)
                elif target.exists():
                    target.unlink()
                if existed:
                    (self.previous / name).rename(target)
        if self.promoted:
            override = self.backup / 'rollback-image.json'
            override.write_text(json.dumps({'services': {'viblio-api': {'image': self.old_image}}}))
            self.compose('up', '-d', '--no-build', '--force-recreate', 'viblio-api', override=override)
        else:
            self.compose('start', 'viblio-api')
        self.wait_healthy()
        print('Versão anterior reativada. Backup preservado em:', self.backup)

    def deploy(self, staged, migrations, release, digest):
        api = self.c.api
        container = self.compose('ps', '-q', 'viblio-api')
        if not container:
            raise UpdateError('API existente não encontrada. Este script é para atualização, não primeira instalação.')
        self.check_space(container)
        identity = self.identity(container)
        env_hash = sha(api / '.env')
        stamp = time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8]
        self.old_image = self.run(['docker', 'inspect', '--format', '{{.Image}}', container])
        # Build before downtime, from the staged sources and the existing env file.
        print('Construindo a imagem nova; a API atual continua ligada…')
        self.c.backups.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.backup = self.c.backups / ('before-' + release + '-' + stamp)
        self.backup.mkdir(mode=0o700)
        self.log = (self.backup / 'update.log').open('ab')
        try:
            rollback_tag = 'viblio-api-rollback:' + stamp.lower()
            self.run(['docker', 'tag', self.old_image, rollback_tag])
            self.old_image = rollback_tag
            (self.backup / 'previous-image.txt').write_text(rollback_tag + '\n')
            self.compose('config', '--quiet', directory=staged)
            self.compose('build', 'viblio-api', directory=staged)
            print('Salvando banco, capas, acervos privados, configuração e código anterior…')
            shutil.copy2(api / '.env', self.backup / 'api.env')
            (self.backup / 'api.env').chmod(0o600)
            shutil.copytree(api, self.backup / 'source', symlinks=True,
                            ignore=shutil.ignore_patterns('.env', '.venv', '.git', '__pycache__',
                                                         '.pytest_cache', '*.dump', '.update.lock'))
            self.stopped = True
            self.compose('stop', 'viblio-api')
            dump = self.backup / 'postgres.dump'
            with dump.open('xb') as output:
                self.run(['docker', 'exec', self.c.postgres, 'pg_dump', '-U', self.c.db_user,
                          '-d', self.c.database, '-Fc'], output=output)
            if not dump.stat().st_size:
                raise UpdateError('Dump do banco vazio.')
            with dump.open('rb') as stream:
                result = subprocess.run(['docker', 'exec', '-i', self.c.postgres, 'pg_restore', '--list'],
                                        stdin=stream, stdout=subprocess.DEVNULL, stderr=self.log)
            if result.returncode:
                raise UpdateError('O arquivo de backup do banco não passou na verificação.')
            self.run(['docker', 'cp', container + ':/data/covers', self.backup / 'covers'])
            if not (self.backup / 'covers' / 'catalog-id').is_file():
                raise UpdateError('Backup de capas incompleto.')
            self.promote(staged)
            self.sql('CREATE TABLE IF NOT EXISTS public.viblio_schema_migrations '
                     '(name text PRIMARY KEY,sha256 text NOT NULL,applied_at timestamptz NOT NULL DEFAULT now());')
            for migration in migrations:
                # Confirm the promoted file exists and still matches the checked package.
                path = api / 'migrations' / migration.name
                if not path.is_file():
                    raise UpdateError('Migração ausente: ' + migration.name)
                print('Aplicando', path.name)
                self.sql(path.read_text())
                self.sql("INSERT INTO public.viblio_schema_migrations(name,sha256) VALUES ('" +
                         path.name + "','" + sha(path) + "');")
            self.compose('up', '-d', '--no-build', '--force-recreate', 'viblio-api')
            print('Aguardando a API ficar healthy…')
            new_container = self.wait_healthy()
            if self.identity(new_container) != identity or sha(api / '.env') != env_hash:
                raise UpdateError('A identidade do catálogo ou a configuração mudou inesperadamente.')
            marker = api / '.viblio-installed.json'
            temp = api / '.viblio-installed.tmp'
            temp.write_text(json.dumps({'version': release, 'sha256': digest, 'updated_at': stamp}) + '\n')
            temp.replace(marker)
            self.stopped = False
            print('Atualização concluída:', release, '· API healthy')
            print('Backup preservado em:', self.backup)
        except BaseException:
            try:
                self.rollback()
            except Exception:
                print('Não foi possível reativar automaticamente. Mantenha os backups; consulte o log em:', self.backup)
            raise
        finally:
            self.log.close()
            self.log = None


def main(argv=None):
    parser = argparse.ArgumentParser(description='Atualizar a API Viblio existente com backup e conferência.')
    parser.add_argument('--version', help='Escolher versão; padrão: mais recente no seu Nextcloud.')
    parser.add_argument('--zip', type=Path, help='Caminho explícito do pacote.')
    parser.add_argument('--sha256', help='Checksum publicado, para uso sem acesso ao GitHub.')
    parser.add_argument('--check', action='store_true', help='Somente conferir; não parar API, migrar ou instalar.')
    args = parser.parse_args(argv)
    os.umask(0o077)
    if os.geteuid() == 0:
        raise UpdateError('Execute como valtecir, sem sudo. O script usa sudo só para ler o Nextcloud, se necessário.')
    config = Config()
    if config.api.is_symlink() or not config.api.is_dir() or config.api.stat().st_uid != os.geteuid():
        raise UpdateError('A pasta /opt/viblio/api precisa existir e pertencer ao seu usuário.')
    if not (config.api / '.env').is_file() or (config.api / '.env').is_symlink():
        raise UpdateError('Configuração .env existente não encontrada ou é um link.')
    for command in ('docker', 'find'):
        if not shutil.which(command):
            raise UpdateError('Comando necessário não encontrado: ' + command)
    if args.version:
        version(args.version)
    updater = Updater(config)
    # Prevent concurrent updates including while waiting for sudo/build/backups.
    with (config.api / '.update.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise UpdateError('Já existe outra atualização em andamento.') from None
        source = (args.zip.absolute() if args.zip else updater.discover(args.version))
        match = PACKAGE.fullmatch(source.name)
        if not match or args.version and match[1] != args.version:
            raise UpdateError('Nome/versão do ZIP não corresponde ao pedido.')
        release = match[1]
        print('Pacote selecionado:', source)
        if args.sha256:
            expected = args.sha256.lower()
            if not re.fullmatch('[a-f0-9]{64}', expected):
                raise UpdateError('SHA-256 inválido.')
        else:
            try:
                with urllib.request.urlopen(CHECKSUMS, timeout=20) as response:
                    raw = response.read(1024**2 + 1)
                if len(raw) > 1024**2:
                    raise UpdateError('Lista de checksums acima do limite.')
                expected = checksum(raw.decode('utf-8'), source.name)
            except (OSError, ValueError) as error:
                raise UpdateError('Não foi possível conferir o SHA-256 publicado. Use --sha256 com o valor recebido de fonte confiável.') from error
        with tempfile.TemporaryDirectory(prefix='viblio-package-') as folder:
            package = Path(folder) / source.name
            updater.copy_package(source, package)
            digest = sha(package)
            if digest != expected:
                raise UpdateError('SHA-256 diferente do publicado. O ZIP não será instalado.')
            with tempfile.TemporaryDirectory(prefix='.viblio-stage-', dir=config.api.parent) as staging:
                staged = Path(staging) / 'package'
                staged.mkdir(mode=0o700)
                files = unpack(package, staged)
                if not files:
                    raise UpdateError('Pacote sem migrações históricas.')
                marker = config.api / '.viblio-installed.json'
                floor = legacy_floor(config.api)
                if not marker.exists() and floor and version(release) < floor:
                    raise UpdateError('Pacote anterior aos arquivos já instalados. Envie a versão nova ao Nextcloud.')
                if marker.exists():
                    installed = json.loads(marker.read_text())
                    if version(release) < version(installed['version']):
                        raise UpdateError('Versão mais antiga que a instalada. Downgrade automático bloqueado.')
                    if release == installed['version'] and digest != installed['sha256']:
                        raise UpdateError('O conteúdo da mesma versão mudou. Publique uma versão nova.')
                    if release == installed['version']:
                        updater.wait_healthy()
                        print('Esta versão já está instalada e healthy. Nada foi alterado.')
                        return
                pending = updater.pending(files)
                print('SHA-256 conferido. Migrações pendentes:', ', '.join(p.name for p in pending) or 'nenhuma')
                updater.compose('config', '--quiet', directory=staged)
                if args.check:
                    print('Conferência concluída. A API não foi parada; banco e instalação não foram alterados.')
                    return
                updater.deploy(staged, pending, release, digest)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('\nOperação interrompida.', file=sys.stderr)
        sys.exit(130)
    except (UpdateError, OSError, ValueError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print('ERRO:', str(error) if isinstance(error, UpdateError) else 'Falha na operação; nenhuma credencial será exibida.', file=sys.stderr)
        sys.exit(1)
