#!/usr/bin/env python3
"""Real Docker update/rollback checks; creates ONLY disposable viblio_test resources."""
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
from unittest.mock import patch
import zipfile

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('viblio_updater',Path(__file__).with_name('atualizar-viblio.py'))
u=importlib.util.module_from_spec(spec);sys.modules[spec.name]=u;spec.loader.exec_module(u)

def run(args,**kwargs):
    return subprocess.run([str(x) for x in args],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kwargs).stdout.decode().strip()

def main():
    name='viblio-updater-test-'+secrets.token_hex(5)
    pg=name+'-postgres';network=name+'-net';database='viblio_test_updater'
    assert name.startswith('viblio-updater-test-') and database.startswith('viblio_test')
    with tempfile.TemporaryDirectory(prefix='viblio-updater-test-') as folder:
        root=Path(folder);api=root/'api';api.mkdir()
        config=u.Config(api=api,backups=root/'backups',nextcloud=root/'nextcloud',project=name,
                        postgres=pg,database=database,health_seconds=30)
        updater=u.Updater(config)
        password=secrets.token_hex(24)
        envfile=root/'postgres.env';envfile.write_text('POSTGRES_DB='+database+'\nPOSTGRES_USER=viblio\nPOSTGRES_PASSWORD='+password+'\n');envfile.chmod(0o600)
        run(['docker','network','create',network])
        try:
            run(['docker','run','-d','--name',pg,'--network',network,'--network-alias','postgres','--env-file',envfile,'postgres:17'])
            for _ in range(40):
                if subprocess.run(['docker','exec',pg,'pg_isready','-U','viblio','-d',database],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
                time.sleep(1)
            updater.sql((ROOT/'viblio-api/tests/schema.sql').read_text())
            updater.sql("INSERT INTO public.valteca(id,titulo) VALUES (131535,'Preservar catálogo');")
            package=ROOT/'downloads/viblio-api-0.2.2.zip'
            assert u.sha(package)=='019169dc2db48591cb873f98f36cd31c19d05fa04bc59c71d253b697587f0c3d'
            u.unpack(package,api)
            compose=(api/'compose.api.yaml').read_text().replace('name: viblio_default','name: '+network)
            compose=compose.replace('"${VIBLIO_BIND_ADDRESS:-0.0.0.0}:${VIBLIO_API_PORT:-8088}:8080"','"127.0.0.1::8080"').replace('interval: 30s','interval: 1s')
            (api/'compose.api.yaml').write_text(compose)
            (api/'.env').write_text('DATABASE_URL=postgresql://viblio:'+password+'@postgres:5432/'+database+'\nVIBLIO_API_TOKEN='+secrets.token_hex(32)+'\nPUBLIC_BASE_URL=http://localhost:8088\nOFFLINE_WHEELS=1\n')
            (api/'.env').chmod(0o600)
            for path in sorted((api/'migrations').glob('*.sql')):updater.sql(path.read_text())
            updater.compose('up','-d','--build','viblio-api')
            container=updater.wait_healthy();identity=updater.identity(container)
            updater.run(['docker','exec',container,'python','-c',"from pathlib import Path;p=Path('/data/covers/private-libraries/test');p.mkdir(parents=True,exist_ok=True);(p/'keep.txt').write_text('private-library-sentinel')"])
            env_hash=u.sha(api/'.env')
            def make_package(release,extra=False):
                dest=root/('viblio-api-'+release+'.zip')
                with zipfile.ZipFile(package) as original,zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED) as out:
                    for info in original.infolist():
                        out.writestr(info,compose.encode() if info.filename=='compose.api.yaml' else original.read(info.filename))
                    if extra:out.writestr('migrations/006_updater_test.sql','ALTER TABLE public.valteca ADD COLUMN IF NOT EXISTS updater_test boolean;')
                return dest
            first=make_package('9.0.0')
            def invoke(path,*args):
                with patch.object(u,'Config',return_value=config):u.main(['--zip',str(path),'--sha256',u.sha(path),*args])
            invoke(first,'--check')
            assert not (api/'.viblio-installed.json').exists() and not config.backups.exists()
            invoke(first)
            assert updater.sql('SELECT count(*) FROM public.viblio_schema_migrations;')=='5'
            assert updater.sql('SELECT titulo FROM public.valteca WHERE id=131535;')=='Preservar catálogo'
            assert u.sha(api/'.env')==env_hash and updater.identity(updater.wait_healthy())==identity
            assert (next(config.backups.iterdir())/'postgres.dump').stat().st_size>0
            assert (next(config.backups.iterdir())/'covers/private-libraries/test/keep.txt').read_text()=='private-library-sentinel'
            before=len(list(config.backups.iterdir()));invoke(first)
            assert len(list(config.backups.iterdir()))==before
            newer=make_package('9.0.1',True)
            actual_wait=u.Updater.wait_healthy;calls=[0]
            def fail_new_health_once(self):
                calls[0]+=1
                if calls[0]==1:raise u.UpdateError('Injected new-version health failure')
                return actual_wait(self)
            with patch.object(u.Updater,'wait_healthy',fail_new_health_once):
                try:invoke(newer)
                except u.UpdateError as error:assert str(error)=='Injected new-version health failure'
                else:raise AssertionError('Expected injected failure')
            assert json.loads((api/'.viblio-installed.json').read_text())['version']=='9.0.0'
            assert updater.sql('SELECT count(*) FROM public.viblio_schema_migrations;')=='6'
            assert updater.identity(updater.wait_healthy())==identity and u.sha(api/'.env')==env_hash
            invoke(newer)
            assert json.loads((api/'.viblio-installed.json').read_text())['version']=='9.0.1'
            assert updater.sql('SELECT count(*) FROM public.viblio_schema_migrations;')=='6'
            assert updater.run(['docker','exec',updater.wait_healthy(),'cat','/data/covers/private-libraries/test/keep.txt'])=='private-library-sentinel'
            print('PASS: real Docker update, private backups, migrations, repeat, injected health failure/old-image rollback and retry; env/catalogue/library preserved.')
        finally:
            subprocess.run(['docker','compose','-p',name,'--project-directory',str(api),'--env-file',str(api/'.env'),'-f',str(api/'compose.api.yaml'),'down','-v','--remove-orphans'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            subprocess.run(['docker','rm','-f',pg],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            subprocess.run(['docker','network','rm',network],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            # These are the test's own rollback tags only; never clean server/user images.
            for file in (root/'backups').glob('*/previous-image.txt'):
                subprocess.run(['docker','image','rm',file.read_text().strip()],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            subprocess.run(['docker','image','rm',name+'-viblio-api'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)

if __name__=='__main__':main()
