# Atualizar API 0.6.1 → 0.7.0

Suporte à **foto real da lombada** sincronizada entre aparelhos. **Não há nova migração SQL** — as 16 migrações anteriores são preservadas integralmente.

## O que muda

- **Biblioteca pessoal (`app/personal_library.py`)**:
  - Aceita o campo opcional `spineData` em livros (`data:image/jpeg;base64,…`), validado como a capa: JPEG real, até 12 MB e no máximo 2048 px no maior lado. Formatos inválidos são recusados com `422`.
  - `spineData` só é aceito em livros (títulos de coleção são recusados).
  - Fusão de três vias e bases compactadas tratam `spineData` por hash, como `coverData`, sem perder imagens nem gerar conflitos falsos.
  - Amigos, clubes e feed **não** recebem a foto da lombada.
- Clientes sem foto de lombada continuam 100% compatíveis (o campo é omitido).

> Atualize a API **antes** de instalar o Viblio 0.7.0 e atualize todos os aparelhos: uma versão antiga do app que sincronizar depois pode remover a foto da lombada do acervo.

Contas, biblioteca, progresso, `.env`, capas, metas e planos anuais são preservados.

## Baixar e atualizar no servidor (Debian)

```bash
curl -fsSL -o ~/viblio-api-0.7.0.zip \
  https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.7.0/viblio-api-0.7.0.zip && \
python3 ~/atualizar-viblio.py --zip ~/viblio-api-0.7.0.zip --sha256 SHA256_PUBLICADO_COM_ESTE_ZIP
```

## Conferir

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl --max-time 10 -fsS http://127.0.0.1:8088/v1/health
curl --max-time 10 -fsS https://api.viblio.com.br/v1/auth/info
```
