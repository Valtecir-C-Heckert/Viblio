# Atualizar Viblio para 0.2.2

Esta versão melhora a entrada, sincroniza alterações pendentes automaticamente com o app aberto e restringe os arquivos JSON a administradores. Atualize a API primeiro e instale o APK por cima, sem desinstalar. Preserva contas, catálogo, capas e bibliotecas. O servidor pessoal não foi acessado pelo desenvolvimento.

## 1. Localizar e copiar do Nextcloud

Envie `viblio-api-0.2.2.zip` para a raiz dos seus arquivos no Nextcloud. No terminal do servidor:

```bash
sudo find /DATA/AppData/nextcloud -type f -name 'viblio-api-0.2.2.zip' 2>/dev/null
sudo cp /DATA/AppData/nextcloud/var/www/html/data/<usuario>/files/viblio-api-0.2.2.zip /tmp/viblio-api-0.2.2.zip
sudo chown <usuario>:<usuario> /tmp/viblio-api-0.2.2.zip
chmod 600 /tmp/viblio-api-0.2.2.zip
unzip -t /tmp/viblio-api-0.2.2.zip
```

Se o `find` mostrar outra pasta, use o caminho encontrado no `cp`. Só continue se a verificação terminar sem erros. Confira também o SHA-256 com o arquivo de checksums da versão.

## 2. Backup antes de substituir

Copie este bloco inteiro. Ele pausa a API durante o backup e a inicia novamente ao terminar; o PostgreSQL continua ligado. O backup tem permissão restrita.

```bash
(
  set -eu
  umask 077
  cd /opt/viblio/api
  viblio_backup="/opt/viblio/backups/before-0.2.2-$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$viblio_backup"
  chmod 700 "$viblio_backup"
  viblio_container="$(docker compose -p viblio-catalog-api -f compose.api.yaml ps -q viblio-api)"
  test -n "$viblio_container"
  cp .env "$viblio_backup/api.env"
  docker compose -p viblio-catalog-api -f compose.api.yaml stop viblio-api
  trap 'docker compose -p viblio-catalog-api -f compose.api.yaml start viblio-api' EXIT
  docker exec viblio-postgres pg_dump -U viblio -d viblio -Fc > "$viblio_backup/postgres.dump"
  test -s "$viblio_backup/postgres.dump"
  docker cp "$viblio_container:/data/covers" "$viblio_backup/covers"
  test -s "$viblio_backup/covers/catalog-id"
  printf 'Backup concluído em %s\n' "$viblio_backup"
)
```

## 3. Extrair e reconstruir

Execute como `<usuario>`, não com `sudo unzip`. O ZIP não contém `.env`. Não execute configure.py novamente, não troque o compose da raiz `/opt/viblio` e não use `down -v`.

```bash
cd /opt/viblio/api
unzip -o /tmp/viblio-api-0.2.2.zip -d /opt/viblio/api
ls -l migrations/005_usernames.sql app/security.py
```

A 0.2.2 não cria migração nova. Para confirmar que a migração 005 está aplicada, execute-a novamente **somente após confirmar o arquivo acima**; ela é repetível e mantém os dados:

```bash
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/005_usernames.sql
docker compose -p viblio-catalog-api -f compose.api.yaml up -d --build
docker compose -p viblio-catalog-api -f compose.api.yaml ps
```

Confira novamente após cerca de 30 segundos. Deve ficar `healthy`. Se não ficar:

```bash
docker logs --tail 60 viblio-catalog-api-viblio-api-1
```

Este roteiro pressupõe as tabelas de contas da 0.2.0 já instaladas, como no servidor atual. Em instalação anterior, siga também as migrações 003/004 do guia 0.2.0 antes de 005.

## 4. Instalar e conferir

Instale `viblio-0.2.2.apk` por cima. Se já estiver conectado, abre a Estante; saia para conferir a nova entrada. O servidor já salvo fica recolhido. Cadastro por convite e recuperação têm formulários próprios; a chave da primeira conta fica em uma opção avançada.

Conectado, mude o progresso/nota e aguarde a sincronização. Sem rede, as alterações continuam locais. Voltando a rede com o Viblio aberto, são enviadas; com o app fechado, são enviadas ao reabrir. Conflitos no mesmo campo pedem escolha em **Conta → Conferir sincronização**. Bibliotecas inalteradas não são reenviadas. Alterações que exigem atualização local podem mostrar brevemente um indicador para impedir edição simultânea.

Normalmente não é preciso importar/exportar arquivos. JSON fica restrito à conta administradora em Estante → ⋮ → Backup / importar; sincronização privada continua disponível para todos. Dados ainda trafegam como snapshots completos quando há alterações; envio incremental de capas é uma etapa posterior.

DNS/HTTPS público, WAF/CDN e acesso para convidados de fora da rede ainda precisam ser configurados no servidor. Consulte [SECURITY.md](SECURITY.md) antes de expor o serviço. Esta atualização não altera esse acesso sozinha.
