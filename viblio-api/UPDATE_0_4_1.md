# Atualizar API 0.4.0 → 0.4.1

Atualize a API antes do APK. Esta versão reorganiza os clubes, permite indicar livros das estantes de todos os integrantes, mantém histórico de leituras concluídas e habilita o compartilhamento automático de leituras.

Novas migrações: **010_club_home.sql** e **011_automatic_activity.sql**. O ZIP inclui 001–011; as nove migrações já publicadas são preservadas. Não recrie banco, contas, `.env` ou volumes.

## Encontrar o pacote no servidor

Depois de enviar `viblio-api-0.4.1.zip` pelo PC ou Nextcloud:

```bash
sudo find /home/valtecir /DATA/AppData/nextcloud/var/www/html/data/valtecir/files -type f -name 'viblio-api-0.4.1.zip' 2>/dev/null
```

Se estiver em `/home/valtecir`, use o atualizador já instalado:

```bash
python3 ~/atualizar-viblio.py --zip /home/valtecir/viblio-api-0.4.1.zip --sha256 SHA256_PUBLICADO_COM_ESTE_ZIP
```

O SHA-256 está no anexo de checksums desta versão e em `downloads/SHA256SUMS`. Não use o hash da 0.4.0.

Se estiver no Nextcloud, copie o caminho encontrado para `/tmp/viblio-api-0.4.1.zip` com `sudo cp`, ajuste o dono para `valtecir:valtecir`, e use esse caminho em `--zip`. Mantenha o script em `~/atualizar-viblio.py`, fora de `/opt/viblio/api`.

O atualizador confere o pacote e o histórico SQL, faz backup de PostgreSQL, capas/snapshots, configuração e código, aplica 010/011 e reconstrói a API usando o mesmo projeto/serviço/volume. Não rode `configure.py`, `down -v` nem substitua `/opt/viblio/compose.yaml`.

## Conferir

```bash
cd /opt/viblio/api
ls -l migrations/010_club_home.sql migrations/011_automatic_activity.sql
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl -fsS http://127.0.0.1:8088/v1/auth/info
```

Deve ficar `healthy`. `auth/info` passa a anunciar `clubs_api: 2`; o healthcheck autenticado anuncia `api_version: 7` e verifica as novas tabelas/colunas. Confira também o domínio HTTPS no celular sem Wi-Fi/VPN. Instale o APK por cima, sem desinstalar ou limpar dados.

## Mudanças

Clube abre na página principal, com livro/capa, **prazo final da leitura**, progresso, etapas/comentários e feed da rodada. O menu **⋯** reúne histórico, participantes e, para admins, configurações. Ao iniciar uma leitura, há confirmação explícita do livro e do prazo final. Encerrar move a rodada para Histórico; capa, etapas, comentários e atividades continuam vinculados à rodada antiga. A próxima votação não reaproveita comentários de outro livro.

A escolha mostra título, autor, capa, páginas e nome do dono dos livros ativos/sincronizados dos integrantes. Não exige amizade e não compartilha notas, metas, avaliações ou o snapshot privado. Indicar valida novamente o dono e o vínculo ao clube. Convites ainda pendentes não têm esse acesso. Remover/sair revoga acesso a livros e miniaturas do grupo; propostas já publicadas permanecem na leitura/histórico.

Leituras passam a ser compartilhadas automaticamente com amizades aceitas, sem switch ou confirmação. A migração ativa isso para contas existentes e novas; não publica retroativamente todas as sessões antigas. O endpoint antigo de opt-out continua compatível, mas retorna compartilhamento ativo e não apaga eventos. Dentro do clube, o feed inclui apenas a rodada/livro escolhido e as etapas abertas; fora dele, a amizade continua necessária para ver atividades.
