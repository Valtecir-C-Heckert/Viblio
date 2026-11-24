# Atualizar API 0.8.3 → 0.9.2

Inclui as **próprias atividades de leitura** do usuário autenticado na linha do tempo do feed (`GET /v1/friends/feed`), misturadas cronologicamente com as atividades dos amigos. **Não há nova migração SQL** — as 16 migrações anteriores são preservadas integralmente.

## O que muda

- **Próprias atividades no feed (`app/activity.py`)**:
  - `GET /v1/friends/feed` passa a incluir o próprio usuário autenticado junto aos amigos aceitos com compartilhamento ativo, mantendo a mesma projeção *allowlisted* (sem notas, e-mail, metas ou campos privados) e incluindo o próprio ID em `visible_actor_ids`.
  - Mesmo se `share_activity` estiver desativado para a conta, o próprio usuário continua registrando e enxergando apenas as suas atividades no seu feed, sem expor nada para amigos.
- **Miniatura de capa própria (`app/friends.py`)**:
  - `GET /v1/friends/{id}/cover` passa a aceitar `id == usuário autenticado` para carregar as capas dos próprios livros exibidos no feed, mantendo bloqueio `404` para terceiros, `Cache-Control: no-store` e exclusão de livros/coleções arquivados.

Contas, biblioteca, progresso, `.env`, capas, metas e planos anuais são preservados.

## Baixar e atualizar no servidor (Debian)

```bash
curl -fsSL -o ~/viblio-api-0.9.2.zip \
  https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.9.2/viblio-api-0.9.2.zip && \
python3 ~/atualizar-viblio.py --zip ~/viblio-api-0.9.2.zip --sha256 SHA256_PUBLICADO_COM_ESTE_ZIP
```

## Conferir

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl --max-time 10 -fsS http://127.0.0.1:8088/v1/health
```
