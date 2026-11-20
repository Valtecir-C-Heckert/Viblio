# Atualização 0.2.13 — Amigos e visual reto

Atualize **a API primeiro**, depois instale o APK por cima do Viblio existente. Mantém https://api.viblio.com.br, contas, sessões, catálogo e estantes. Não precisa recriar Cloudflare/DNS ou a instalação do PostgreSQL.

## O que muda

Amigos substitui Conta na navegação inferior, usando o mesmo ícone. Conta passa a ser a **última opção de ⋮ na Estante e no Livro atual**. Adicione pelo nome de usuário exato; o destinatário aceita ou recusa. Pedidos enviados podem ser cancelados. Só depois do aceite os dois podem consultar as estantes e a leitura atual um do outro. A capa pessoal do livro atual aparece ao lado do nome. Remover amizade encerra novos acessos nos dois sentidos.

A estante do amigo é somente consulta, com filtros, busca e volumes de coleções. Não importa, edita ou troca os livros da biblioteca local. Não compartilha e-mail, senha, notas, avaliação pessoal, sessões detalhadas, backups ou configurações. Convites continuam servindo para criar contas; um convite não cria amizade nem libera estantes.

Visual com controles e painéis mais retos, fundo escuro neutro e destaque extraído da capa do **seu livro atual**, com ajuste de contraste. Trocar o atual atualiza o destaque; sem capa usa um tom dourado neutro. Preserva a disposição das capas/lombadas, madeira, organização, filtros e funcionamento do leitor/timer. A cor não depende do livro atual do amigo.

A migração **007_friends.sql** acrescenta somente a tabela de amizades e um índice. Migrações anteriores e dados existentes ficam preservados. Os arquivos 001–007 estão completos no ZIP; os arquivos publicados anteriormente não foram alterados.

## Opção A — atualizador já instalado

1. No GitHub autenticado → Releases → Viblio 0.2.13, baixe `viblio-api-0.2.13.zip` e o arquivo de checksums do mesmo Release.
2. Envie o ZIP com o nome original ao seu Nextcloud.
3. Como `<usuario>`, use o atualizador instalado **fora de /opt/viblio/api**:

```bash
python3 ~/atualizar-viblio.py --version 0.2.13
```

Se a lista de checksums não puder ser baixada do GitHub privado, copie **o SHA-256 do ZIP** do arquivo de checksums autenticado e informe-o:

```bash
python3 ~/atualizar-viblio.py --version 0.2.13 --sha256 'COLE_AQUI_O_SHA256_DO_ZIP_PUBLICADO'
```

O script localiza o ZIP no Nextcloud, confere o pacote, faz backup de banco/.env/capas/estantes privadas, aplica as migrações em ordem e recria somente a API. Confira antes com `--check`, se desejar. Não execute o script inteiro com sudo. Consulte `UPDATER.md`. Não faça também a opção B se o atualizador concluir com sucesso.

## Opção B — atualização manual da instalação existente

### 1. Backup, como `<usuario>`

```bash
sudo install -d -m 700 -o <usuario> -g <usuario> /opt/viblio/backups
VIBLIO_UPDATE_BACKUP="/opt/viblio/backups/antes-0.2.13-$(date +%Y%m%d-%H%M%S)"
(
  set -e
  umask 077
  mkdir "$VIBLIO_UPDATE_BACKUP"
  docker exec viblio-postgres pg_dump -U viblio -d viblio -Fc > "$VIBLIO_UPDATE_BACKUP/database.dump"
  docker exec -i viblio-postgres pg_restore --list < "$VIBLIO_UPDATE_BACKUP/database.dump" > /dev/null
  cp /opt/viblio/api/.env "$VIBLIO_UPDATE_BACKUP/.env"
  docker volume inspect viblio-catalog-api_viblio-covers > /dev/null
  docker run --rm --mount type=volume,source=viblio-catalog-api_viblio-covers,target=/data,readonly --mount "type=bind,source=$VIBLIO_UPDATE_BACKUP,target=/backup" alpine:3.22 tar -czf /backup/covers-and-private.tar.gz -C /data .
  sudo chown <usuario>:<usuario> "$VIBLIO_UPDATE_BACKUP/covers-and-private.tar.gz"
  chmod 600 "$VIBLIO_UPDATE_BACKUP/.env" "$VIBLIO_UPDATE_BACKUP/covers-and-private.tar.gz"
)
```

Se qualquer comando falhar, pare e mantenha a instalação existente. Não envie `.env`, senhas ou dumps ao chat.

### 2. Localizar, copiar e verificar

```bash
sudo find /DATA/AppData/nextcloud -type f -name 'viblio-api-0.2.13.zip' 2>/dev/null
```

Se estiver na raiz dos seus arquivos do Nextcloud:

```bash
sudo cp /DATA/AppData/nextcloud/var/www/html/data/<usuario>/files/viblio-api-0.2.13.zip /tmp/viblio-api-0.2.13.zip
sudo chown <usuario>:<usuario> /tmp/viblio-api-0.2.13.zip
chmod 600 /tmp/viblio-api-0.2.13.zip
sha256sum /tmp/viblio-api-0.2.13.zip
unzip -t /tmp/viblio-api-0.2.13.zip
```

Ajuste o cp se o find indicar outra pasta. Compare o SHA-256 com o anexo de checksums do mesmo Release antes de extrair.

### 3. Extrair e confirmar a migração

```bash
cd /opt/viblio/api
unzip -o /tmp/viblio-api-0.2.13.zip -d /opt/viblio/api
ls -l app/friends.py migrations/007_friends.sql
```

Se os dois arquivos existirem e a instalação já usa a API 0.2.9:

```bash
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/007_friends.sql
```

É repetível e não migra usuários antigos do Valteca. Se ainda usa API anterior à 0.2.9, conclua a migração 006 seguindo `UPDATE_0_2_9.md` antes da 007, ou use o atualizador que aplica os arquivos pendentes em ordem. Não troque a chave, o endereço público ou DATABASE_URL.

### 4. Recriar somente a API e conferir

```bash
cd /opt/viblio/api
OFFLINE_WHEELS=1 docker compose -p viblio-catalog-api -f compose.api.yaml up -d --build
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl -fsS https://api.viblio.com.br/v1/auth/info
```

Espere ficar **healthy**. O healthcheck inclui a tabela de amizades; o JSON público deve conter `"friends_api":1`. Preserve `.env`, catalog-id, projeto `viblio-catalog-api`, volume `viblio-catalog-api_viblio-covers` e `/opt/viblio/compose.yaml`. Não rode configure.py, não use down -v e não recrie o PostgreSQL.

## APK e teste com um amigo

Instale `viblio-0.2.13.apk` por cima, **sem desinstalar**. Seu amigo também precisa do APK novo, na mesma instalação do Viblio, para aceitar o pedido. Não precisam importar JSON.

1. Confira o nome de usuário de cada conta. Conta está em Estante → ⋮ → Conta, na última opção.
2. Em Amigos, informe o nome do outro usuário e toque em Adicionar.
3. A outra pessoa abre Amigos e aceita. Antes disso as estantes continuam inacessíveis.
4. Atualize a lista, toque no nome e consulte a estante. A capa ao lado do nome vem da última leitura sincronizada daquela pessoa.
5. Remova a amizade se quiser: Amigos → pessoa → ⋮ → Remover amizade. Novas consultas e downloads das capas passam a ser recusados.

O APK continua usando a biblioteca pessoal no aparelho quando estiver offline. Amigos requer conexão e conta ativa. Se instalar só o APK antes de atualizar o servidor, sua estante continua disponível e Amigos informa que a API precisa ser atualizada.
