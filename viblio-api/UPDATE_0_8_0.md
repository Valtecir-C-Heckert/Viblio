# Atualizar API 0.7.0 → 0.8.0

Sincronização de **capas e lombadas separada do JSON de acervo** (`/v1/me/media`, endereçada por SHA-256) + capas mais nítidas em Amigos e Clubes (`360×540 Q86`). **Não há nova migração SQL** — as 16 migrações anteriores são preservadas integralmente.

## O que muda

- **Mídia separada do snapshot (`app/personal_library.py`, `app/main.py`, `app/security.py`)**:
  - Novos endpoints privados por conta: `POST /v1/me/media/check`, `PUT /v1/me/media/{sha256}` (JPEG binário ≤ 12 MB, ≤ 2048 px, validação SHA-256 + Pillow + cota configurável via `VIBLIO_USER_MEDIA_MAX_MB`) e `GET /v1/me/media/{sha256}` (`Cache-Control: private, immutable`).
  - Snapshots gravados em disco passam a referenciar `"media:<sha256>"` em vez de duplicar Base64 nas 10 revisões (reduzindo ~90% do disco e acelerando o sync em ~10×).
  - **100% retrocompatível:** clientes `0.8.0+` usam o header `X-Viblio-Media: 1`; clientes antigos (`<= 0.7.4`) continuam funcionando normalmente com hidratação automática.
  - Limpeza segura de mídia órfã (apenas arquivos não referenciados por nenhuma das 10 revisões mantidas e com mais de 24 h).
- **Amigos e Clubes (`app/friends.py`, `app/clubs.py`)**:
  - Leitura direta de `media/{sha256}.jpg` e miniaturas elevadas de `160×240 Q80` para **`360×540 Q86`** (até 256 KB).

Contas, biblioteca, progresso, `.env`, capas, lombadas, metas e planos anuais são preservados.

## Baixar e atualizar no servidor (Debian)

```bash
curl -fsSL -o ~/viblio-api-0.8.0.zip \
  https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.8.0/viblio-api-0.8.0.zip && \
python3 ~/atualizar-viblio.py --zip ~/viblio-api-0.8.0.zip --sha256 SHA256_PUBLICADO_COM_ESTE_ZIP
```

## Conferir

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl --max-time 10 -fsS http://127.0.0.1:8088/v1/health
curl --max-time 10 -fsS https://api.viblio.com.br/v1/auth/info
```
