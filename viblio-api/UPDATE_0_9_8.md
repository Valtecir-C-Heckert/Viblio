# Atualizar API 0.9.5 → 0.9.8

Adiciona suporte a reações nos cards do Feed (`👏 🔥 ❤️ 📖`) e ao resumo leve de atividades sociais (`GET /v1/social/summary`) usado pelo badge na aba Amigos e pelas notificações periódicas self-hosted (sem Firebase). Inclui a migração SQL aditiva e idempotente `migrations/017_feed_reactions.sql`, preservando todas as 16 migrações anteriores.

## O que muda

- **Nova migração (`migrations/017_feed_reactions.sql`)**:
  - Cria a tabela `public.viblio_activity_reactions` (`activity_id`, `user_id`, `reaction`, `created_at`) com validação `CHECK (reaction IN ('clap','fire','heart','book'))`, chave primária composta `(activity_id, user_id)` e índice em `activity_id`.
- **Reações no Feed (`app/activity.py`, `app/security.py`)**:
  - `GET /v1/friends/feed` passa a incluir o objeto agregado `reactions: {counts, mine, total}` em cada item (em uma única consulta agregada, sem N+1).
  - Novos endpoints `PUT /v1/friends/feed/{activity_id}/reaction` e `DELETE /v1/friends/feed/{activity_id}/reaction` com rate limit dedicado (`feed-reaction`, 60 req/min) e autorização estrita (somente o autor do evento ou amigo aceito com atividade visível).
- **Resumo de atividade social (`app/activity.py`, `app/main.py`, `app/accounts.py`)**:
  - Novo endpoint `GET /v1/social/summary` retornando `{friend_requests, club_invites, open_votes, unread_messages, total}` em uma única consulta SQL.
  - `/v1/health` valida `public.viblio_activity_reactions` e anuncia `feed_api: 2` e `social_summary_api: 1` (também em `/v1/auth/info`).

Contas, biblioteca, progresso, `.env`, capas, metas, prateleiras e planos anuais são preservados.

## Baixar e atualizar no servidor (Debian)

```bash
curl -fsSL -o ~/viblio-api-0.9.8.zip \
  https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.9.8/viblio-api-0.9.8.zip && \
python3 ~/atualizar-viblio.py --zip ~/viblio-api-0.9.8.zip --sha256 SHA256_PUBLICADO_COM_ESTE_ZIP
```

## Conferir

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl --max-time 10 -fsS http://127.0.0.1:8088/v1/health
```
