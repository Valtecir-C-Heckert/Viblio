# Atualizar API 0.8.0 → 0.8.3

Otimização de velocidade para carregamento de **capas de amigos e clubes** (cache LRU *thread-safe* com *single-flight* e liberação das capas do semáforo `bulk=4`). **Não há nova migração SQL** — as 16 migrações anteriores são preservadas integralmente.

## O que muda

- **Cache de fontes e miniaturas sem *thrashing* nem *thundering herd* (`app/friends.py`)**:
  - `cover_sources` elevado de `lru_cache(maxsize=2)` sem lock para cache LRU *thread-safe* com lock por `(owner, filename)` e `maxsize=32` — elimina releitura e descompactação repetida do `.json.gz` quando há 3+ amigos no Feed ou requisições simultâneas para o mesmo amigo.
  - `thumbnail` elevado de `maxsize=256` para cache LRU *thread-safe* *single-flight* `maxsize=512`.
- **Admissão dedicada para capas privadas (`app/security.py`)**:
  - Requisições `GET` de capas (`/v1/friends/{id}/cover`, `/v1/clubs/{id}/proposals/{id}/cover`, `/v1/clubs/{id}/library-cover`) deixam de disputar o semáforo `bulk` de 4 slots (reservado para snapshots/sync), evitando erros `503` ao abrir o Feed ou a estante de um amigo.
  - Limite por usuário para capas elevado de `90/min` para `240/min`, preservando integralmente autenticação, checagem de amizade e `Cache-Control: no-store`.

Contas, biblioteca, progresso, `.env`, capas, metas e planos anuais são preservados.

## Baixar e atualizar no servidor (Debian)

```bash
curl -fsSL -o ~/viblio-api-0.8.3.zip \
  https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.8.3/viblio-api-0.8.3.zip && \
python3 ~/atualizar-viblio.py --zip ~/viblio-api-0.8.3.zip --sha256 SHA256_PUBLICADO_COM_ESTE_ZIP
```

## Conferir

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl --max-time 10 -fsS http://127.0.0.1:8088/v1/health
```
