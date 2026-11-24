# Atualizar API 0.9.2 → 0.9.5

Permite que administradores de clube editem a página-alvo e excluam etapas/metas de leituras em andamento, e expõe metadados públicos (`my_page`, `catalog_id` e `own_book_id` apenas do próprio usuário) para sincronização bidirecional entre o clube e o livro atual no app. **Não há nova migração SQL** — as 16 migrações anteriores são preservadas integralmente.

## O que muda

- **Edição e exclusão de etapas (`app/clubs.py`)**:
  - `PATCH /v1/clubs/{c}/stages/{id}` passa a aceitar alteração de `page_limit` (dentro do total de páginas do livro e sem duplicar outra etapa da mesma rodada), preservando comentários e marcas de leitura.
  - Novo endpoint `DELETE /v1/clubs/{c}/stages/{id}` para administradores ativos em leituras em andamento (`state == 'reading'`), removendo mensagens e leituras da etapa em cascata e encerrando streams SSE ativos com `event: revoked`.
- **Sincronização Clube ↔ Livro Atual (`app/clubs.py`, `app/main.py`, `app/accounts.py`)**:
  - `GET /v1/clubs` inclui `my_page`, `book.catalog_id` e `book.own_book_id` (somente quando o livro pertence ao usuário autenticado).
  - `GET /v1/clubs/{c}` inclui `catalog_id` e `own_book_id` (somente do próprio usuário) em `proposals`.
  - `/v1/health` e `/v1/auth/info` anunciam `clubs_api: 4`.

Contas, biblioteca, progresso, `.env`, capas, metas e planos anuais são preservados.

## Baixar e atualizar no servidor (Debian)

```bash
curl -fsSL -o ~/viblio-api-0.9.5.zip \
  https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.9.5/viblio-api-0.9.5.zip && \
python3 ~/atualizar-viblio.py --zip ~/viblio-api-0.9.5.zip --sha256 SHA256_PUBLICADO_COM_ESTE_ZIP
```

## Conferir

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl --max-time 10 -fsS http://127.0.0.1:8088/v1/health
```
