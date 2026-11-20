# Atualizar API 0.4.9 → 0.4.10

Correção de desempenho e precisão das **Recomendações (Descobrir)** e suporte a **livro adicionado à estante** no **Feed de amigos** (`api_version: 11`):

- Nova migração `migrations/014_recommendation_indexes.sql`: cria índices GIN trigram (`CONCURRENTLY`, sem travar leituras/escritas) em `genero` e `subgenero`, além de usar diretamente o índice existente `viblio_author_search` (sem `coalesce` nem ordenação de tabela inteira).
- Isolamento estrito de seções em `GET /v1/books/recommendations`: cada carrossel (*Autor*, *Subgênero*, *Gênero*) exibe **apenas** livros daquele termo (nunca mistura outros autores/gêneros na mesma seção), reconhece nomes em formato `"Sobrenome, Nome"` e exclui todas as edições de livros já presentes na estante (por `catalogId`, `work_key`, `ISBN`, título-base/subtítulo e preservação de volumes distintos).
- Nova migração `migrations/015_feed_added.sql`: permite eventos `kind = 'added'` em `public.viblio_activity` para mostrar novos livros adicionados à estante no Feed de amigos (ignorando sincronização inicial e importações em massa).

Contas, biblioteca, progresso, `.env`, capas e volumes são preservados.

## Baixar e atualizar direto no servidor (Debian)

```bash
curl -fsSL -o ~/viblio-api-0.4.10.zip \
  https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.4.10/viblio-api-0.4.10.zip && \
python3 ~/atualizar-viblio.py --zip ~/viblio-api-0.4.10.zip --sha256 SHA256_PUBLICADO_COM_ESTE_ZIP
```

## Conferir

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl --max-time 10 -fsS http://127.0.0.1:8088/v1/auth/info
curl --max-time 10 -fsS https://api.viblio.com.br/v1/auth/info
```
