# Atualizar API 0.4.2 → 0.4.3

Atualize a API antes do APK 0.4.6. Esta versão adiciona suporte aditivo ao redesenho
da aba **Clubes**: contadores de mensagens não lidas por etapa/clube, resumo da etapa
atual e da votação nos cards, quem enviou cada convite (`invited_by`), quem indicou
cada livro na votação (`proposed_by`) e respostas encadeadas (`reply_to_id`) com
escopo estrito à mesma etapa.

Nova migração aditiva: **`migrations/012_club_redesign.sql`**. As migrações
**001–011** permanecem intactas. Contas, biblioteca, progresso, configuração e
volumes são preservados.

## Enviar pelo PC (PowerShell)

```powershell
scp "$env:USERPROFILE\Downloads\viblio-api-0.4.3.zip" <usuario>@<ip-do-servidor>:~/
ssh <usuario>@<ip-do-servidor>
```

## Atualizar no servidor (Debian)

```bash
ls -lh ~/viblio-api-0.4.3.zip
python3 ~/atualizar-viblio.py --zip ~/viblio-api-0.4.3.zip --sha256 SHA256_PUBLICADO_COM_ESTE_ZIP
```

O atualizador faz backup automático do banco, arquivos privados/capas, `.env` e
código, aplica apenas a migração `012_club_redesign.sql` e valida o healthcheck.

## Conferir

```bash
cd /opt/viblio/api
ls -l migrations/012_club_redesign.sql
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl --max-time 10 -fsS http://127.0.0.1:8088/v1/auth/info
curl --max-time 10 -fsS https://api.viblio.com.br/v1/auth/info
```

O serviço deve ficar `healthy`, `auth/info` deve informar `clubs_api: 3` e o
healthcheck autenticado deve informar `api_version: 9`. Só depois instale o APK
0.4.6 por cima, sem desinstalar nem limpar dados. Não rode `configure.py` ou
`down -v`.
