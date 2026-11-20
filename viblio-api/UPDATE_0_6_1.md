# Atualizar API 0.5.0 → 0.6.1

Atualização de **segurança, estabilidade e desempenho** (auditoria completa do aplicativo, API e atualizador). **Não há nova migração SQL** — todas as 16 migrações anteriores são preservadas integralmente.

## Principais melhorias

- **Segurança da API (`app/security.py`, `app/main.py`, `app/accounts.py`)**:
  - Corrige o padrão de detecção de SSE de discussões para aceitar UUIDs com e sem hífens (evitando bloqueio de slots HTTP gerais).
  - Rejeita uploads > 16 KiB sem cabeçalho `Authorization: Bearer` antes de ler o corpo inteiro e aplica prazo de leitura de 15s em rotas pequenas e `statement_timeout = 15000ms` no PostgreSQL.
  - Aumenta o custo de hash `scrypt` de novas senhas (`n=32768`, mantendo compatibilidade retroativa com hashes existentes `n=16384`), valida existência de tokens de convite/recuperação antes de executar `scrypt` e corrige comparações UTF-8 constantes no tempo e rate-limit de reset por peer.
  - Suporte opcional `VIBLIO_TRUST_CF_IP=1` (confia em `CF-Connecting-IP` apenas quando a conexão vem de loopback) e bind padrão de `compose.api.yaml` em `127.0.0.1` (`${VIBLIO_BIND_ADDRESS:-127.0.0.1}`).
- **Robustez, privacidade e desempenho (`app/personal_library.py`, `app/activity.py`, `app/clubs.py`, `app/recommendations.py`, `app/friends.py`, `app/search.py`)**:
  - Rejeita `NaN`/`Infinity`, JSON profundamente aninhado (> 12 níveis), tipos malformados e inteiros fora de 64 bits (`2^63-1`) antes de atingir o banco.
  - Otimiza feed de amigos e clubes em SQL (`unnest`/`LATERAL`), respeita coleções arquivadas no progresso dos clubes, oculta `rating` e duração no feed do clube e bloqueia edição de etapas em rodadas concluídas.
  - Move geração de miniaturas Pillow fora da transação SQL, trata `DecompressionBombError`, preserva `Cache-Control` imutável em `/v1/covers/` e adiciona `ORDER BY` determinístico em paginações.
- **Atualizador e empacotamento (`scripts/atualizar-viblio.py`, `scripts/configure.py`, `Dockerfile`)**:
  - Proteção real de bytes em streaming contra zip bombs, validação `resolve().is_relative_to()`, abertura com `O_NOFOLLOW` + `fstat`, permissão `0600` no backup de `.env`, `psql -v` parametrizado e bloqueio de downgrade HTTPS→HTTP ao baixar checksums.
  - Imagem base Python fixada por digest multiplataforma no `Dockerfile`.

Contas, biblioteca, progresso, `.env`, capas, metas e planos anuais são preservados.

## Baixar e atualizar no servidor (Debian)

```bash
curl -fsSL -o ~/viblio-api-0.6.1.zip \
  https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.6.1/viblio-api-0.6.1.zip && \
python3 ~/atualizar-viblio.py --zip ~/viblio-api-0.6.1.zip --sha256 SHA256_PUBLICADO_COM_ESTE_ZIP
```

Opcionalmente, atualize também o script `~/atualizar-viblio.py` com a versão endurecida incluída no pacote:

```bash
cp /opt/viblio/api/scripts/atualizar-viblio.py ~/atualizar-viblio.py
chmod 755 ~/atualizar-viblio.py
```

## Conferir

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl --max-time 10 -fsS http://127.0.0.1:8088/v1/health
curl --max-time 10 -fsS https://api.viblio.com.br/v1/auth/info
```
