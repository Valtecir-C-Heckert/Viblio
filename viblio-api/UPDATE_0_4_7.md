# Atualizar API 0.4.6 → 0.4.7

Atualização de desempenho para a aba **Amigos**, **Feed** e **Clubes** (sem migração nova — `001–012` permanecem intactas):

- Pool limitado de conexões PostgreSQL (`LifoQueue`) com encerramento limpo no shutdown, eliminando handshakes repetidos por requisição.
- Remoção de `FOR UPDATE` em endpoints `GET` somente leitura dentro de clubes (evita serialização entre detalhe do clube, feed, capas e mensagens).
- Eliminação de releitura repetida do JSONB da estante em `GET /v1/friends/feed` e pré-filtragem de atividades em `GET /v1/clubs/{c}/feed`.
- Aumento seguro do cache de capas (`cover_sources` e miniaturas) em `friends.py` para evitar descompressão repetida quando há múltiplos amigos.

Contas, biblioteca, progresso, `.env`, capas e volumes são preservados.

## Enviar pelo PC (PowerShell)

```powershell
scp "$env:USERPROFILE\Downloads\viblio-api-0.4.7.zip" <usuario>@<ip-do-servidor>:~/
ssh <usuario>@<ip-do-servidor>
```

## Atualizar no servidor (Debian)

```bash
python3 ~/atualizar-viblio.py --zip ~/viblio-api-0.4.7.zip --sha256 SHA256_PUBLICADO_COM_ESTE_ZIP
```

## Conferir

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl --max-time 10 -fsS http://127.0.0.1:8088/v1/auth/info
curl --max-time 10 -fsS https://api.viblio.com.br/v1/auth/info
```
