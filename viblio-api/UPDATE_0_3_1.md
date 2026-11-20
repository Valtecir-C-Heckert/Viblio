# Atualizar a API para 0.3.1

Esta versão envia a página e o total de páginas do livro atual na lista de amigos. Estantes e consultas existentes continuam compatíveis. Nenhuma migração nova: inclui 001–007, intactas. Não recrie contas, banco ou volumes.

## No servidor, com o ZIP no Nextcloud

Use o terminal de `<servidor>`. Não use `$env:USERPROFILE` nesse terminal; essa variável é do PowerShell do PC.

```bash
sudo find /DATA/AppData/nextcloud/var/www/html/data/<usuario>/files -type f -name 'viblio-api-0.3.1.zip'
```

Se estiver na raiz do Nextcloud:

```bash
sudo cp /DATA/AppData/nextcloud/var/www/html/data/<usuario>/files/viblio-api-0.3.1.zip /tmp/viblio-api-0.3.1.zip
sudo chown "$(id -un):$(id -gn)" /tmp/viblio-api-0.3.1.zip
chmod 600 /tmp/viblio-api-0.3.1.zip
```

Se o `find` mostrar outra pasta, use esse caminho no comando `cp`.

O atualizador deve ficar fora de `/opt/viblio/api`. Se já existe em `~/atualizar-viblio.py`, utilize-o. A instalação da API 0.2.13 também inclui uma cópia:

```bash
test -f ~/atualizar-viblio.py || cp /opt/viblio/api/scripts/atualizar-viblio.py ~/atualizar-viblio.py
```

Execute o comando de atualização publicado na validação desta versão, com o SHA-256 do ZIP:

```bash
python3 ~/atualizar-viblio.py --zip /tmp/viblio-api-0.3.1.zip --sha256 SHA256_DO_ZIP
```

O atualizador valida o pacote, interrompe apenas a API conforme necessário, guarda backup de banco/capas/snapshots privados/.env e preserva `.env`, projeto Compose e volume. Consulte `UPDATER.md` se seu ambiente tiver caminhos diferentes. Não use `down -v`, não execute `configure.py` novamente e não substitua `/opt/viblio/compose.yaml`.

## Conferir

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl -fsS http://127.0.0.1:8088/v1/auth/info
```

A API deve ficar `healthy`. No APK 0.3.1, abra Amigos e atualize a lista: cada livro atual compartilhado passa a mostrar a página. Não envie tokens, senhas ou `.env` ao conferir.

Instale o APK por cima, sem desinstalar ou limpar dados. A versão de APK anterior continua compatível com a API nova.
