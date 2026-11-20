# Atualizar API 0.4.10 → 0.5.0

Grande atualização de **Busca Inteligente (`search_api: 2`)**, **Recomendações Precisas + Rolagem Infinita (`recommendations_api: 2`)** e **Plano Anual de Leitura (`api_version: 12`)**:

- Nova migração `migrations/016_search_saga_index.sql`: cria índice GIN trigram (`CONCURRENTLY`, sem travar leituras/escritas) na coluna `saga`.
- Nova busca unificada `GET /v1/books?query=...&kind=...` (`app/search.py`):
  - Multi-palavra e combinada (`título + autor + saga`, ordem invertida), ranking por relevância, filtro de tipo no servidor (`book`/`manga`/`comic`), paginação (`next_cursor`) e fallback tolerante a erros de digitação via `pg_trgm`.
- Recomendações `GET /v1/books/recommendations` aprimoradas (`app/recommendations.py`):
  - Travadas automaticamente ao tipo do livro atual na aba `Todos` (nunca puxa autores de mangá quando você está lendo um livro).
  - Omite a seção `"Mais de <Autor>"` se o autor atual não tiver outros livros no catálogo (nunca substitui por outro autor aleatório).
  - Nova seção **`Mais da saga <Saga>`** ordenada por número do volume crescente (`ordem`).
  - Subgêneros resilientes a hífens (`Space-Opera`, `Não-Ficção`), flexões (`Distopia` ↔ `Distópica`), busca em `subgenero` + `genero` e enriquecimento automático pelo `catalogId`.
- Novo endpoint `GET /v1/books/recommendations/more` para rolagem lateral infinita sob demanda em cada carrossel.

Contas, biblioteca, progresso, `.env`, capas, metas e planos anuais (`SHELF_ANNUAL_PLAN:*`) são preservados.

## Baixar e atualizar direto no servidor (Debian)

```bash
curl -fsSL -o ~/viblio-api-0.5.0.zip \
  https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.5.0/viblio-api-0.5.0.zip && \
python3 ~/atualizar-viblio.py --zip ~/viblio-api-0.5.0.zip --sha256 SHA256_PUBLICADO_COM_ESTE_ZIP
```

## Conferir

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl --max-time 10 -fsS http://127.0.0.1:8088/v1/auth/info
curl --max-time 10 -fsS https://api.viblio.com.br/v1/auth/info
```
