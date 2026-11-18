# Atualizar a API para capas alternativas

Para quem já instalou a API 0.1.4 em `/opt/viblio/api`. O APK 0.1.8 exige esta atualização para a galeria. A biblioteca pessoal continua no celular.

1. Baixe `viblio-api-0.1.8.zip` no servidor. Extraia sobre `/opt/viblio/api`, preservando o `.env` existente. O ZIP não contém `.env`. Não execute novamente `scripts/configure.py`: sua chave e endereço continuam iguais. Não substitua `/opt/viblio/compose.yaml`.
2. No terminal do servidor, execute:

```bash
cd /opt/viblio/api
# Backup privado no servidor; confira se o comando termina sem erro.
umask 077
docker exec viblio-postgres pg_dump -U viblio -d viblio -Fc > "viblio-before-covers-$(date +%Y%m%d-%H%M%S).dump"
# Pare aqui se o backup falhar. Execute os próximos comandos um por vez.
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/003_cover_variants.sql
# Continue somente se a migração terminar sem erro.
docker compose -p viblio-catalog-api -f compose.api.yaml up -d --build
docker compose -p viblio-catalog-api -f compose.api.yaml ps
```

Mantenha o mesmo nome de projeto `viblio-catalog-api` e o volume existente. Não use `down -v`. A migração é aditiva: mantém a capa principal e liga as imagens já enviadas ao mesmo livro. Pode ser repetida sem duplicar relações. A imagem base Docker precisa estar disponível; as dependências incluídas são para Linux x86_64/Python 3.12, como no pacote anterior.

3. Quando a API aparecer `healthy`, instale o APK por cima do Viblio atual, sem desinstalar. A configuração do catálogo permanece. Na Pesquisa toque em **Ver capas**, ou edite um item vinculado e toque em **Capas do catálogo**. Escolha uma imagem e salve.

Se o container não ficar saudável, confira os logs localmente:

```bash
docker compose -p viblio-catalog-api -f compose.api.yaml logs --tail=50 viblio-api
```

Não compartilhe tokens ou `.env`. Um backup completo da API inclui o dump PostgreSQL e o volume `viblio-catalog-api_viblio-covers`, onde estão as imagens e a identidade do catálogo. Nenhum comando deste guia apaga o volume. Esta atualização foi testada em banco isolado; não foi executada remotamente no seu servidor.
