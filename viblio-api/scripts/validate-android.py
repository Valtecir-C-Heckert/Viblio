#!/usr/bin/env python3
"""Own disposable PostgreSQL/API only; the UI script refuses personal phones."""
import os,secrets,socket,subprocess,sys,tempfile,time,urllib.request
from pathlib import Path
root=Path(__file__).resolve().parents[1];container='viblio-native-test-'+secrets.token_hex(5);process=None
with tempfile.TemporaryDirectory(prefix='viblio-native-') as folder:
    folder=Path(folder);password=secrets.token_hex(24)
    envfile=folder/'postgres.env';envfile.write_text('POSTGRES_DB=viblio_test\nPOSTGRES_USER=viblio\nPOSTGRES_PASSWORD='+password+'\n');envfile.chmod(0o600)
    try:
        subprocess.run(['docker','run','-d','--name',container,'--env-file',str(envfile),'-p','127.0.0.1::5432','postgres:17'],check=True,stdout=subprocess.DEVNULL)
        for _ in range(40):
            if subprocess.run(['docker','exec',container,'pg_isready','-U','viblio','-d','viblio_test'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
            time.sleep(1)
        port=subprocess.check_output(['docker','port',container,'5432/tcp'],text=True).strip().rsplit(':',1)[1]
        environment=os.environ.copy();environment['VIBLIO_TEST_DATABASE_URL']=f'postgresql://viblio:{password}@127.0.0.1:{port}/viblio_test';environment['PYTHONPATH']=str(root)
        subprocess.run([sys.executable,'-m','pytest','-q'],cwd=root,env=environment,check=True)
        with socket.socket() as sock:sock.bind(('127.0.0.1',0));api_port=sock.getsockname()[1]
        token='test-only-'+'a'*40
        environment.update(DATABASE_URL=environment['VIBLIO_TEST_DATABASE_URL'],VIBLIO_API_TOKEN=token,COVER_DIR=str(folder/'covers'),PUBLIC_BASE_URL=f'http://10.0.2.2:{api_port}')
        with (folder/'api.log').open('w') as log:
            process=subprocess.Popen([sys.executable,'-m','uvicorn','app.main:create_app','--factory','--host','127.0.0.1','--port',str(api_port),'--no-access-log'],cwd=root,env=environment,stdout=log,stderr=log)
            for _ in range(50):
                try:
                    req=urllib.request.Request(f'http://127.0.0.1:{api_port}/v1/health',headers={'Authorization':'Bearer '+token})
                    with urllib.request.urlopen(req,timeout=1) as response:
                        if response.status==200:break
                except Exception:time.sleep(.2)
            else:raise RuntimeError('Isolated API did not become ready.')
            subprocess.run([str(root.parent/'viblio/scripts/validate-ui.sh'),'emulator-5554',f'http://10.0.2.2:{api_port}',token],cwd=root.parent,env=environment,check=True)
    finally:
        if process is not None:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait()
        subprocess.run(['docker','rm','-f',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
