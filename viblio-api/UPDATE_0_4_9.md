# Atualizar API 0.4.7 → 0.4.9

Atualização para **Recomendações (Descobrir)**, lista isolada **Quero comprar** (`013_wishlist.sql`, `api_version: 10`) e limpeza de eventos vazios no Feed de amigos:

- Nova migração idempotente `migrations/013_wishlist.sql` criando `public.viblio_wishlist` separada da estante (`books`).
- Novos endpoints autenticados:
  - `GET /v1/books/recommendations` — recomendações ancoradas no livro atual (com fallback para último concluído / favorito), organizadas por **Autor**, **Subgênero** e **Gênero**, excluindo livros já presentes na estante.
  - `GET /v1/me/wishlist`, `POST /v1/me/wishlist` e `DELETE /v1/me/wishlist/{book_id}` — lista dedicada **Quero comprar** (limite de 500 itens por conta).
- Filtro em `app/activity.py` para ignorar sessões de leitura instantâneas sem páginas (`0 páginas · 0 s`).

Contas, biblioteca, progresso, `.env`, capas e volumes são preservados.

## Enviar pelo PC

### Linux (Fedora / bash)

```bash
scp ~/Downloads/viblio-api-0.4.9.zip <usuario>@<ip-do-servidor>:~/
ssh <usuario>@<ip-do-servidor>
```

### Windows (PowerShell)

```powershell
scp "$env:USERPROFILE\Downloads\viblio-api-0.4.9.zip" <usuario>@<ip-do-servidor>:~/
ssh <usuario>@<ip-do-servidor>
```

## Atualizar no servidor (Debian)

```bash
python3 ~/atualizar-viblio.py --zip ~/viblio-api-0.4.9.zip --sha256 SHA256_PUBLICADO_COM_ESTE_ZIP
```

## Conferir

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl --max-time 10 -fsS http://127.0.0.1:8088/v1/auth/info
curl --max-time 10 -fsS https://api.viblio.com.br/v1/auth/info
```
