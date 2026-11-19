# API do novo Viblio

## Atualização 0.4.7

Otimização de sincronização em Amigos, Feed e Clubes: pool limitado de conexões
PostgreSQL, remoção de `FOR UPDATE` em leituras `GET` de clubes, eliminação de
detoast repetido no Feed e cache ampliado de miniaturas. Sem migração nova;
`001–012` permanecem intactas. [Guia](UPDATE_0_4_7.md).

## Atualização 0.4.6

Suporte aditivo ao redesenho da aba Clubes: resumo da etapa/votação e contador de
não lidas nos cards, autor do convite (`invited_by`), autor da indicação
(`proposed_by`) e respostas encadeadas (`reply_to_id`) restritas à mesma etapa.
Requer **`012_club_redesign.sql`**; histórico 001–011 intacto. `clubs_api: 3`,
health `api_version: 9`. Atualize a API antes do APK 0.4.6. [Guia](UPDATE_0_4_6.md).

## Atualização 0.4.2

Comentários de etapas ao vivo por SSE autenticado, com reconexão e revogação de
acesso. Não há migração nova; 001–011 permanecem intactas. `club_events_api: 1`,
health `api_version: 8`. Atualize a API antes do APK. [Guia](UPDATE_0_4_2.md).
O serviço permanece com um worker; múltiplos workers exigem barramento compartilhado.

## Atualização 0.4.1

Principal/Histórico dos clubes, feed por rodada, livros de todos os integrantes para votação e compartilhamento automático de leitura. Requer **010 e 011**; histórico 001–009 intacto. `clubs_api: 2`, health `api_version: 7`. [Guia](UPDATE_0_4_1.md) · [Verificação](../docs/VALIDATION_VIBLIO_0_4_1.md). Regras de opt-in descritas na seção histórica 0.4.0 foram substituídas pelo compartilhamento automático na 0.4.1.

## Atualização 0.4.0

Feed de leitura opt-in, diretório de nomes e clubes por convite, com múltiplos admins, votação, prazos, etapas e discussões. Requer migrações 008 e 009 antes do APK. [Atualizar no Debian](UPDATE_0_4_0.md) · [Segurança e limites](SECURITY.md). API health version 6; auth/info anuncia feed_api e clubs_api. APKs anteriores continuam compatíveis.

## Atualização 0.3.1

A lista de amigos inclui a página e o total de páginas do livro atual, pela mesma projeção pública e autorização já existentes. Sem notas, avaliações, sessões ou dados de conta. Sem migração nova; histórico completo 001–007 mantido. [Guia de atualização](UPDATE_0_3_1.md).

## Atualização 0.2.13

Amizades com aceite, estantes de consulta e miniaturas privadas. Requer migração 007 antes do APK 0.2.13. [Guia de atualização](UPDATE_0_2_13.md). Não recrie contas, catálogo, volumes ou arquivo .env.

Backend independente em Python/FastAPI para o APK. Usa `public.valteca` como catálogo compartilhado no PostgreSQL 17 informado. Não usa o Valteca antigo, Supabase, Next.js, contas antigas ou serviços pagos. A biblioteca pessoal continua no SQLite, com sincronização opcional em tabelas novas `viblio_*`. Não migra `biblioteca_usuario` nem autenticação do Valteca antigo.

Para atualizar repetidamente a API pelo Nextcloud com backup automático, use o [script reutilizável](UPDATER.md).

Já tem a API instalada? Use o [guia de atualização 0.2.3](UPDATE_0_2_3.md), preservando configuração e volume.

## Instalar no Debian, ao lado do PostgreSQL existente

Extraia este pacote em **`/opt/viblio/api`**, sem substituir `/opt/viblio/compose.yaml`. Use a rede existente `viblio_default` e o host interno `postgres`. O Compose deste pacote cria somente a API e seu volume de capas.

```bash
cd /opt/viblio/api
# Backup antes das migrações; o arquivo fica no seu servidor.
docker exec viblio-postgres pg_dump -U viblio -d viblio -Fc > viblio-before-api.dump
# SQL aditivo: novos IDs e índices, sem reescrever os 131 mil livros.
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/001_api.sql
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/002_search.sql
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/003_cover_variants.sql
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/004_accounts.sql
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/005_usernames.sql
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/006_cover_groups.sql
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/007_friends.sql
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/008_activity_feed.sql
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/009_book_clubs.sql
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/010_club_home.sql
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/011_automatic_activity.sql
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/012_club_redesign.sql
python3 scripts/configure.py
docker compose -p viblio-catalog-api -f compose.api.yaml up -d --build
docker compose -p viblio-catalog-api -f compose.api.yaml ps
```

O configurador pede o endereço que o celular alcança e a senha do PostgreSQL somente no seu terminal. Gera uma chave aleatória e grava `.env` com permissão 600. Abra esse arquivo **localmente** e copie somente `VIBLIO_API_TOKEN` para o APK. Não publique/envie `.env`, senha ou chave. Se a senha original foi definida em `/opt/viblio/compose.yaml` ou no `.env` antigo do PostgreSQL, consulte-a localmente; este pacote não muda essa senha.

A API usa a porta 8088 por padrão. Ajuste `VIBLIO_API_PORT` se necessário e use o mesmo endereço em `PUBLIC_BASE_URL` e no APK. `VIBLIO_BIND_ADDRESS` permite limitar o bind a uma interface. Use na rede local/Tailscale; para endereço público, configure HTTPS. O APK aceita HTTP apenas para IPs privados, localhost, `.local` e `.ts.net`; não encaminha a chave a sites externos ou redirecionamentos.

No Viblio: **Estante → ⋮ → Backup / importar → Catálogo do servidor**, ou **Adicionar livro → Conectar catálogo**. Informe o endereço e a chave. Android 6+ para guardar a chave no Keystore; Android 5 mantém toda a biblioteca e o cadastro local.

## Fluxo e contrato

- `GET /v1/health`: autenticado; verifica banco/tabela auxiliar e retorna identidade persistente do catálogo.
- `GET /v1/books?q=...&author=...&isbn=...&limit=20&cursor=...`: autenticado; busca parcial literal, acentos/caixa normalizados, limite máximo 50, paginação por ID. IDs bigint são enviados como string, inclusive acima de 2^53.
- `GET /v1/books/{id}/covers`: autenticado; capa principal e até 100 alternativas do mesmo registro, com identidade do catálogo.
- `POST /v1/books`: autenticado; multipart com `metadata` JSON e `cover` opcional. Campos aceitos: `request_id`, `catalog_id`, `title`, `author`, `genre`, `subgenres`, `publisher`, `saga`, `sequence`, `pages`, `isbn`, `kind` (`book`, `manga`, `comic`) e `is_volume`. `request_id` UUID torna a repetição idempotente. Não recebe progresso/notas pessoais.
- `GET /covers/<uuid-sem-hifens>.jpg`: acesso à imagem por nome aleatório, sem credencial na URL. Não lista o volume e recusa nomes/caminhos arbitrários.

Selecionar uma sugestão preenche somente o que a API conhece. `metricas_totais` vira páginas apenas quando `metrica_tipo` indica páginas; volumes/minutos não são convertidos. `ordem` é preenchida apenas se inteira e positiva. Campos ausentes continuam vazios. Nenhum ISBN ou dado fictício é inventado. O formulário atual exige páginas para livros comuns e as pede se não estiverem no catálogo.

Capas externas são carregadas diretamente no APK e guardadas no cache de imagens atual. Uma escolha manual tem prioridade. Não há busca/download em massa pelo servidor. Capas manuais são enviadas, validadas por conteúdo, limitadas a 12 MB/20 milhões de pixels, convertidas em JPEG e gravadas em volume persistente com nome UUID. O PostgreSQL guarda URL/referência, nunca Base64. O backup JSON do aparelho continua portátil com capas embutidas; isso não é armazenamento de imagem no banco do servidor.

Livro novo sem vínculo: criar/resolver registro e retornar ID. Duplicação é evitada sob transação/lock por ISBN normalizado, ou correspondência exata de título/autor/editora quando não há ISBN, respeitando páginas e número quando ambos são conhecidos; correspondências parciais servem apenas para sugestões. ISBNs diferentes podem ser edições diferentes. Catálogo existente com edição pessoal/capa manual não é sobrescrito: a capa local e sua cópia enviada são preservadas sem alterar a capa pública do registro original.

Biblioteca e cadastro manual continuam disponíveis sem conexão. A fila de envio fica persistida no aparelho, e pode ser reenviada em Catálogo do servidor. A resposta atualiza apenas o vínculo, preservando identificador local, histórico e páginas. O vínculo vai no backup JSON como campos opcionais da versão 5; leitores anteriores ignoram esses campos, preservando os demais dados. A chave não entra no backup.

## Armazenamento e manutenção

Volume `viblio-catalog-api_viblio-covers`: inclui capas e identidade estável `catalog-id`. Preserve-o junto do dump PostgreSQL; não use `docker compose down -v`. A identidade impede associar IDs iguais de catálogos diferentes. Não substitua o volume ao atualizar. `.env` guarda configuração e chave; não é incluído no ZIP ou na imagem.

As migrações criam extensões `unaccent`/`pg_trgm`, função normalizada, sequência/default para os novos IDs e tabelas auxiliares de idempotência/capas. Os índices de busca são construídos CONCURRENTLY. As migrações podem ser repetidas; se `id` já tiver um default diferente, a primeira para para revisão. Alterar o dicionário unaccent posteriormente exige reconstruir os índices normalizados.

A API roda sem root, sem capabilities adicionais e com autenticação Bearer de uma instalação pessoal. A partir da 0.2.0, sessões individuais acessam somente a própria biblioteca. A chave de instalação mantém a compatibilidade do catálogo legado e autoriza o primeiro administrador. Uma falha entre gravar arquivo e confirmar a transação pode deixar um arquivo órfão; nenhuma rotina de limpeza/migração de imagens em massa é executada.

## Desenvolvimento e testes

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.lock
.venv/bin/pip install pytest httpx
VIBLIO_TEST_PYTHON=.venv/bin/python scripts/test-api.sh
```

O script inicia um PostgreSQL 17 isolado com credenciais temporárias e apaga somente esse contêiner ao terminar. Os testes recusam banco que não comece com `viblio_test`; não os rode contra seu catálogo pessoal. Testa esquema real, migrações repetidas, busca, concorrência, nulos, IDs grandes, upload, isolamento de escolhas pessoais e autenticação.

O pacote ZIP inclui wheels verificadas para Linux x86_64/Python 3.12. Nesse servidor o configurador ativa OFFLINE_WHEELS=1, permitindo instalar dependências sem consultar o PyPI durante o build. O repositório de fontes usa instalação online por padrão; para outros processadores mantenha OFFLINE_WHEELS=0. A imagem base Python ainda precisa estar disponível no Docker.

## Capas alternativas — 0.1.8

`public.viblio_book_covers` liga imagens ao ID existente, sem duplicar o livro. A migração 003 recupera uploads registrados pela API anterior e pode ser repetida. A principal continua em `valteca.capa_url`; trocar a escolha na Estante não a altera. Uma nova imagem enviada fica disponível no catálogo compartilhado desta instalação. Imagens iguais após conversão são listadas uma vez por livro. A seleção pessoal, notas e progresso continuam locais.

Na Pesquisa, use **Ver capas** antes de adicionar. No editor de um livro/volume vinculado, use **Capas do catálogo** e salve. Para a capa de um título por volumes, a escolha é feita a partir do volume 1 vinculado. Arquivos do celular continuam disponíveis. Volumes novos sem vínculo precisam de numeração para compartilhar a capa; volumes diferentes não são reunidos pelo título da série. Um ISBN distinto continua podendo representar outra edição, não apenas uma capa alternativa.

Na 0.1.8, a API retornava `api_version: 2`. APKs anteriores continuam compatíveis com busca/cadastro; o APK novo informa a necessidade de atualização ao abrir a galeria com uma API antiga. O banco guarda referências, e o volume guarda as imagens. Preserve ambos nos backups. A galeria não migra imagens locais nunca enviadas: elas entram ao salvar/enviar pelo APK.


## Contas por convite — 0.2.0

API versão 3, `account_api: 1`. Instalação/atualização e limites completos: [UPDATE_0_2_0.md](UPDATE_0_2_0.md). Migração 004 aditiva/repetível; catálogo existente preservado. Cadastro manual e capas pessoais deixam de ser publicados automaticamente no catálogo após ativar contas. Convidados consultam o catálogo com a própria sessão.

Rotas: `GET /v1/auth/info`, `POST /v1/auth/bootstrap` (chave de instalação, somente uma vez), `/v1/auth/register` (convite), `/v1/auth/login`, `/v1/auth/logout`, `/v1/auth/reset-password`, `GET /v1/me`. Administrador gera/lista/revoga convites em `/v1/admin/invitations`; lista contas em `/v1/admin/users` e gera recuperação em `/v1/admin/users/{id}/recovery`. Não há endpoint para ler a biblioteca de outra pessoa, inclusive para o administrador.

Senhas scrypt com salt; sessões opacas de 30 dias; hashes de tokens guardados no banco. Convites padrão de sete dias/uso único/revogáveis. Recuperação de 24 horas/uso único revoga sessões anteriores. Limitação de tentativas por e-mail e origem direta; não confia em X-Forwarded-For fornecido pelo cliente. Sem SMTP ou verificação de e-mail nesta beta. Recuperação local do administrador existente: `python -m app.reset_admin`, interativo no container.

Acervo: `GET /v1/me/library/status`, `GET /v1/me/library?revision=...` e `POST /v1/me/library/sync` (gzip, UUID do pedido, revisão de origem e snapshot). Transação/lock por conta, combinação de campos independentes e HTTP 409 sem gravar em conflitos. Resoluções explícitas vinculadas à revisão atual. Envios automáticos usam revisão estrita para não sobrepor mudanças remotas.

Backups privados ficam em `private-libraries/<user-id>` no volume de capas; não são servidos pelo endpoint público. Capas JPEG incorporadas são preservadas sem recompressão. PostgreSQL guarda revisões, índices e bases compactas com hash das capas, sem Base64. Dez versões completas/mil bases compactas por conta. Snapshot até 32 MiB descompactado/24 MiB comprimido; demais limites no guia. Arquivos de segurança locais são limitados a três por perfil. Backup completo de desastre precisa reunir dump PostgreSQL, volume e configuração protegida.

O arquivo JSON passa ao esquema 6, importando 1 a 6. A Central antiga pode recusá-lo e não recebe APK nesta entrega. Sem KOReader, migração de autenticação antiga ou acesso ao servidor pessoal.

Para validar Android e API: monte `:viblio:assembleDebugAndroidTest`, inicie emulador API 28 vazio e rode `scripts/validate-android.py` com o Python do venv e `ANDROID_HOME`. Ele usa apenas PostgreSQL/API descartáveis; o helper recusa telefones reais.


## Nomes de usuário — 0.2.1

[Atualização](UPDATE_0_2_1.md). A migração 005 preserva contas e bibliotecas, adicionando um nome opcional para contas anteriores e único sem distinguir caixa. Novos APKs pedem o nome no cadastro. `POST /v1/auth/login` aceita `identifier` (nome ou e-mail) ou o campo `email` legado, nunca os dois. `PATCH /v1/me` permite alterar apenas o nome da própria conta, sem mudar seu ID. Validação ASCII de 3 a 32 caracteres: letras, números, ponto e sublinhado; primeiro caractere alfanumérico. Normaliza em minúsculas. O e-mail continua privado no perfil e utilizável para entrar.

`/v1/auth/info` preserva `account_api: 1` e acrescenta `username_login: true`, permitindo aos APKs antigos continuar entrando por e-mail. O healthcheck exige a coluna nova. Sem atribuição automática de nomes para pessoas existentes, sem migração de bibliotecas e sem mudança de senhas.


## Atualização 0.2.2

Entrada redesenhada, JSON somente para administradores no Viblio e sincronização automática das alterações pendentes com o app aberto. Acervo inalterado não é reenviado; conflitos continuam explícitos. API com cotas por IP/conta, admissão limitada e respostas privadas sem cache. [Guia](UPDATE_0_2_2.md) · [Segurança e limites](SECURITY.md).


## Inventário das coleções — 0.2.3

A sincronização aceita o campo opcional expectedVolumes em cada coleção e recalcula os contadores com os volumes ativos e numerados, inclusive depois da mesclagem entre aparelhos. Mantém esquema 6 e compatibilidade de contas; não modifica o catálogo nem exige nova migração PostgreSQL. Atualize os APKs junto para obter o cálculo e filtros novos. [Guia](UPDATE_0_2_3.md).

## Galeria por obra — 0.2.9

API version 4: galeria GET /v1/books/{id}/covers aceita limit (1–50) e cursor (0–10000), retorna next_cursor e metadados das edições. A migração 006 adiciona chave normalizada e índices concorrentes, sem reescrever valteca. Agrupa por work_key quando confiável ou título/autor equivalentes, preservando tipo e número; não mistura autores desconhecidos, work_keys conflitantes ou volumes distintos. URLs iguais e fingerprints de uploads são deduplicados. Consultas limitadas a 1000 edições e 5000 uploads sinalizam truncated quando ultrapassadas.

Uploads têm alias público /v1/covers/{filename}, compatível com o caminho do túnel. A busca/galeria normaliza URLs antigas de uploads conhecidos em suas respostas, sem modificar capa_url. /covers continua no backend para compatibilidade. Não lê capas pessoais de snapshots para publicar na galeria e não baixa imagens externas no servidor.

[Atualizar no Debian](UPDATE_0_2_9.md). Aplique 006 antes de recriar a API. Dependências, porta, projeto, volumes, contas e migrações 001–005 permanecem os mesmos.
