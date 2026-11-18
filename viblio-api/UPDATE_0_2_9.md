# Atualização 0.2.9 — galeria da obra e leitura por período

Atualize **a API primeiro**, depois instale o APK 0.2.9 sobre o Viblio existente. O endereço continua https://api.viblio.com.br. Não precisa recriar túnel/DNS, contas ou catálogo. Preserve `.env`, catalog-id, projeto Compose e volumes.

## O que muda

A galeria passa a consultar edições equivalentes, não apenas o ID escolhido na busca. Usa identificador de obra quando conhecido; sem ele, título+autor normalizados. Tipos e números de volume precisam coincidir. Autores desconhecidos e identificadores de obra conflitantes não são agrupados por suposição. Não funde ou apaga registros e não troca valteca.capa_url. ISBN/editora/páginas aparecem nas opções de capa quando disponíveis.

Galeria paginada, retirando URLs repetidas e uploads com fingerprint igual. Consultas limitadas a 1.000 edições/5.000 uploads por obra e até 50 opções por página (APK usa 30). Não baixa imagens externas no servidor para comparar pixels: URLs diferentes do mesmo desenho podem continuar como opções. Se os nomes/autores estiverem inconsistentes e não houver work_key comum, a associação continua conservadora.

Uploads passam a ser apresentados em `/v1/covers/…`, que funciona com o caminho `/v1/.*` do túnel. A rota antiga `/covers/…` continua no backend para compatibilidade. URLs antigas de uploads conhecidos são normalizadas na resposta da busca/galeria, sem reescrever o catálogo.

No APK, segure a capa dentro do livro/volume, ou use o menu → Trocar capa. Escolha Capas do catálogo ou Imagem do celular. A escolha fica na estante pessoal. Livros locais sem vínculo pesquisam a edição e pedem escolha antes de associar. Em títulos de coleção, o atalho usa o volume 1.

Capas vazias de exemplares já vinculados podem receber a principal da própria edição em segundo plano, em lotes de até oito por abertura. Uma capa não vazia nunca é substituída por esse preenchimento; a gravação é condicional ao vínculo e à capa ainda estarem iguais. Cadastros sem vínculo ou edições sem capa principal exigem escolha. As alterações entram na sincronização pessoal existente, não publicam imagens pessoais no catálogo.

Estatísticas → Por período: Dia/Semana/Mês/Ano, anterior/próximo/período atual, exemplares/volumes/livros com leitura registrada, páginas e tempo cronometrados. A visão anual mostra meses; nos demais períodos, Ver meses do ano abre esse resumo. Conta registros/datas salvos, não inventa a data de conclusão de livros marcados como lidos sem histórico. Datas manuais contam exemplares, sem criar páginas/tempo. Usa o dia editado da sessão quando existir; semana de segunda a domingo. Mantém o escopo Todos/Livros/Mangás/Quadrinhos e considera os exemplares ativos da Estante, como o resumo anterior.

## 1. Baixar e guardar no Nextcloud

No GitHub autenticado → Releases → Viblio 0.2.9, baixe **viblio-api-0.2.9.zip** e envie ao seu Nextcloud com esse nome. Baixe também o APK, mas atualize o servidor antes de instalar.

## 2. Backup no servidor, como valtecir

```bash
sudo install -d -m 700 -o valtecir -g valtecir /opt/viblio/backups
VIBLIO_UPDATE_BACKUP="/opt/viblio/backups/antes-0.2.9-$(date +%Y%m%d-%H%M%S)"
(
  set -e
  umask 077
  mkdir "$VIBLIO_UPDATE_BACKUP"
  docker exec viblio-postgres pg_dump -U viblio -d viblio -Fc > "$VIBLIO_UPDATE_BACKUP/database.dump"
  docker exec -i viblio-postgres pg_restore --list < "$VIBLIO_UPDATE_BACKUP/database.dump" > /dev/null
  cp /opt/viblio/api/.env "$VIBLIO_UPDATE_BACKUP/.env"
  docker volume inspect viblio-catalog-api_viblio-covers > /dev/null
  docker run --rm --mount type=volume,source=viblio-catalog-api_viblio-covers,target=/data,readonly --mount "type=bind,source=$VIBLIO_UPDATE_BACKUP,target=/backup" alpine:3.22 tar -czf /backup/covers-and-private.tar.gz -C /data .
  sudo chown valtecir:valtecir "$VIBLIO_UPDATE_BACKUP/covers-and-private.tar.gz"
  chmod 600 "$VIBLIO_UPDATE_BACKUP/.env" "$VIBLIO_UPDATE_BACKUP/covers-and-private.tar.gz"
  echo "Backup concluído: $VIBLIO_UPDATE_BACKUP"
)
```

Se houver erro, pare. Não envie o conteúdo do `.env`, tokens ou dumps ao chat.

## 3. Localizar, copiar e verificar o pacote

```bash
sudo find /DATA/AppData/nextcloud -type f -name 'viblio-api-0.2.9.zip' 2>/dev/null
```

Se estiver na raiz do seu Nextcloud, use:

```bash
sudo cp /DATA/AppData/nextcloud/var/www/html/data/valtecir/files/viblio-api-0.2.9.zip /tmp/viblio-api-0.2.9.zip
sudo chown valtecir:valtecir /tmp/viblio-api-0.2.9.zip
chmod 600 /tmp/viblio-api-0.2.9.zip
sha256sum /tmp/viblio-api-0.2.9.zip
unzip -t /tmp/viblio-api-0.2.9.zip
```

Se o find indicar outra pasta, ajuste o cp. Compare o SHA-256 com o arquivo de checksums do mesmo Release, antes de extrair.

## 4. Extrair e confirmar a migração nova

```bash
cd /opt/viblio/api
unzip -o /tmp/viblio-api-0.2.9.zip -d /opt/viblio/api
ls -l app/cover_gallery.py migrations/006_cover_groups.sql
```

Somente se ambos existirem, execute:

```bash
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/006_cover_groups.sql
```

A migração adiciona uma função de normalização e dois índices. Usa CREATE INDEX CONCURRENTLY: não execute com `-1` ou dentro de BEGIN/COMMIT. Pode ser repetida. As migrações 001–005 estão completas e imutáveis no pacote, mas não precisam ser repetidas neste servidor já atualizado para contas/usuários. Se estiver vindo de uma API anterior à 0.2.3, consulte os guias correspondentes antes.

## 5. Recriar somente a API e conferir

```bash
cd /opt/viblio/api
OFFLINE_WHEELS=1 docker compose -p viblio-catalog-api -f compose.api.yaml up -d --build
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl -fsS https://api.viblio.com.br/v1/auth/info
```

Espere ficar **healthy**. O healthcheck também verifica a função da migração 006. Não use down -v, não rode configure.py, não troque DATABASE_URL/VIBLIO_API_TOKEN/PUBLIC_BASE_URL ou o nome do projeto, e não substitua /opt/viblio/compose.yaml.

Se você já usa o atualizador independente, ele pode instalar a versão 0.2.9 verificando os checksums e as migrações completas; siga UPDATER.md. Não precisa fazer a sequência manual duas vezes.

## 6. Instalar o APK e conferir

Instale `viblio-0.2.9.apk` por cima, sem desinstalar. Entre na mesma conta se necessário; não precisa importar JSON.

- Abra 1984 → segure a capa → Capas do catálogo. Confira editoras/ISBN e selecione a capa do seu exemplar.
- Repita em outra conta e confirme que sua escolha não mudou a capa da outra pessoa. Uma capa antiga incorreta precisa ser trocada: o app não adivinha qual imagem foi perdida anteriormente.
- Em Estatísticas → Por período, compare Dia/Semana/Mês/Ano e a lista de leituras. Sem sessões/datas históricas, não haverá como atribuir leituras passadas a meses por estimativa.

Não houve migração do SQLite ou mudança do esquema de sincronização pessoal. A comparação real com as seis edições do servidor e a aparência no telefone são conferências após a implantação; os testes de desenvolvimento usam dados descartáveis equivalentes, nunca sua biblioteca pessoal.
