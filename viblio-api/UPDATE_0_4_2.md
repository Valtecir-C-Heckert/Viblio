# Atualizar API 0.4.1 → 0.4.2

Atualize a API antes do APK. Comentários de etapas passam a receber atualizações ao
vivo por SSE autenticado. Não há migração nova: o pacote mantém 001–011 idênticas
às publicadas. Contas, progresso, configuração e volumes são preservados.

## Enviar pelo PC (PowerShell do Windows)

Execute no PC onde estão os arquivos em Downloads, fora do SSH:

```powershell
scp "$env:USERPROFILE\Downloads\viblio-api-0.4.2.zip" <usuario>@<ip-do-servidor>:~/
ssh <usuario>@<ip-do-servidor>
```

## Atualizar no servidor (Debian)

```bash
ls -lh ~/viblio-api-0.4.2.zip
python3 ~/atualizar-viblio.py --zip ~/viblio-api-0.4.2.zip --sha256 SHA256_PUBLICADO_COM_ESTE_ZIP
```

Copie o SHA-256 correspondente a este ZIP do arquivo de checksums entregue com a
versão; não reutilize o de outra versão. O atualizador faz backup do banco, dos
arquivos privados/capas, de `.env` e do código antes de reconstruir o serviço.

Se enviou pelo Nextcloud, localize primeiro o pacote:

```bash
sudo find /home/<usuario> /DATA/AppData/nextcloud/var/www/html/data/<usuario>/files -type f -name 'viblio-api-0.4.2.zip' 2>/dev/null
```

Copie o caminho encontrado para `/tmp/viblio-api-0.4.2.zip`, ajuste o dono para
`<usuario>:<usuario>` e use esse caminho em `--zip`. O script deve permanecer em
`~/atualizar-viblio.py`, fora de `/opt/viblio/api`.

## Conferir

```bash
cd /opt/viblio/api
ls -l migrations/010_club_home.sql migrations/011_automatic_activity.sql
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl --max-time 10 -fsS http://127.0.0.1:8088/v1/auth/info
curl --max-time 10 -fsS https://api.viblio.com.br/v1/auth/info
```

O serviço deve ficar `healthy`, e `auth/info` deve incluir `club_events_api: 1`.
O healthcheck autenticado informa `api_version: 8`. Só depois instale o APK por
cima, sem desinstalar nem limpar dados. Não rode `configure.py` ou `down -v`.

## Conexões ao vivo

O app abre uma conexão apenas na discussão visível e recupera as mensagens
persistidas ao reconectar. Publicações/exclusões notificam depois do commit.
Saída/troca de conta cancela a conexão; perda de participação ou encerramento da
discussão revoga acesso. Sessões também são revalidadas durante a conexão ociosa.

O Compose/Dockerfile mantém um único worker, necessário ao barramento local.
Não aumente workers sem configurar um barramento compartilhado. Há até 32 streams
e três por conta, separados da capacidade das requisições normais. O proxy deve
transmitir `text/event-stream` sem buffer/cache; o endpoint envia `no-store` e
`X-Accel-Buffering: no`. Túnel/DNS e portas existentes não são alterados.
