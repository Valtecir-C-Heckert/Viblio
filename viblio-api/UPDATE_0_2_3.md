# Viblio 0.2.3 — coleções completas e livro atual

Atualize a API e depois instale o APK por cima, sem desinstalar. Preserva perfis, capas, notas e histórico. Não inclui integração de pesquisa de capas com MangaDex ou outros provedores; a busca manual existente permanece disponível.

## O que mudou

- Estante → Mangás/Quadrinhos → Filtrar: **Coleção → Todas / Completas / Incompletas / Total não informado**. Esse filtro é separado de **Leitura**. Ter todos os volumes não significa que todos estejam lidos.
- **Livro atual** é o primeiro atalho no menu inferior. Abre diretamente a leitura atual e indica quando nenhum livro foi escolhido. Confirma antes de sair de um formulário não salvo e aguarda organização/arquivos em processamento.
- O total de referência da coleção fica estável. Adicionar um volume faltante reduz a quantidade automaticamente; editar o mesmo volume ou cadastrar uma segunda cópia do mesmo número não reduz novamente. Remover/restaurar exemplares também recalcula a disponibilidade.
- A API recalcula os contadores depois de combinar acervos de aparelhos diferentes. Alterações realmente incompatíveis no total continuam pedindo escolha, sem somar contadores incorretamente.

## Atualizar pelo script existente

Envie **viblio-api-0.2.3.zip** ao seu Nextcloud, com esse nome. No servidor, como valtecir, execute:

```bash
python3 ~/atualizar-viblio.py --version 0.2.3
```

O script confere a publicação, faz backups do banco/volume/.env/código, aplica somente migrações pendentes e verifica healthy. Se a consulta de checksum pela Internet falhar, use `--sha256` com o valor publicado para **este ZIP** em downloads/SHA256SUMS; não reutilize o hash da 0.2.2. Consulte [UPDATER.md](UPDATER.md).

Nesta versão **não existe nova migração SQL do PostgreSQL**. As migrações 001–005 permanecem intactas. O APK migra automaticamente o SQLite local de 10 para 11, acrescentando a coluna opcional `expected_volumes`. A sincronização mantém esquema JSON 6 com um campo adicional `expectedVolumes`; as bibliotecas pessoais continuam separadas do catálogo público e entre contas. Atualize também o APK nos demais aparelhos para todos usarem o mesmo cálculo.

## Se ainda não instalou o script

Faça o backup de banco, volume e configuração conforme a seção 2 de [UPDATE_0_2_2.md](UPDATE_0_2_2.md), antes de substituir arquivos. Localize o ZIP no Nextcloud e copie como já fazemos:

```bash
sudo find /DATA/AppData/nextcloud -type f -name 'viblio-api-0.2.3.zip' 2>/dev/null
sudo cp /DATA/AppData/nextcloud/var/www/html/data/valtecir/files/viblio-api-0.2.3.zip /tmp/viblio-api-0.2.3.zip
sudo chown valtecir:valtecir /tmp/viblio-api-0.2.3.zip
chmod 600 /tmp/viblio-api-0.2.3.zip
unzip -t /tmp/viblio-api-0.2.3.zip
```

Se o find indicar outra pasta, use esse caminho no cp. Confira também o SHA-256 publicado para o pacote. Após o backup e as verificações, extraia como valtecir:

```bash
cd /opt/viblio/api
unzip -o /tmp/viblio-api-0.2.3.zip -d /opt/viblio/api
ls -l app/personal_library.py migrations/005_usernames.sql
docker compose -p viblio-catalog-api -f compose.api.yaml up -d --build
docker compose -p viblio-catalog-api -f compose.api.yaml ps
```

Este caminho pressupõe as migrações até 005 já aplicadas. Espere ficar healthy, então instale `viblio-0.2.3.apk` por cima. Não apague volumes, não execute configure.py novamente e preserve `.env` e `/opt/viblio/compose.yaml`.

## Conferir seus títulos

A migração usa os dados já disponíveis: obtidos + faltantes. Ela não pesquisa nem inventa o total da edição. Se o contador antigo já estava desatualizado, revise **Editar título → Volumes faltantes** uma vez; salvar redefine o total de referência. Campo vazio mantém o total desconhecido.

A contagem considera os números distintos de 1 até o total informado. Um volume sem número ou fora desse intervalo não preenche um número faltante. Duplicatas são mantidas como exemplares, mas não completam outros números. Total não informado permanece separado de incompleta e nunca vira zero faltantes por suposição. Capas manuais e a regra da capa principal pelo volume 1 foram preservadas.
