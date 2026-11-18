# Atualizar a API para 0.4.0

Atualize a API antes de instalar o APK 0.4.0. Inclui Feed, diretório de pessoas e clubes com vários administradores. As migrações **008_activity_feed.sql** e **009_book_clubs.sql** são novas, aditivas e repetíveis. 001–007 permanecem intactas; contas, catálogo e bibliotecas são preservados.

## ZIP no Nextcloud → servidor

No terminal de `srvValtecir`:

```bash
sudo find /DATA/AppData/nextcloud/var/www/html/data/valtecir/files -type f -name 'viblio-api-0.4.0.zip'
```

Use o caminho encontrado; se estiver na raiz:

```bash
sudo cp /DATA/AppData/nextcloud/var/www/html/data/valtecir/files/viblio-api-0.4.0.zip /tmp/viblio-api-0.4.0.zip
sudo chown "$(id -un):$(id -gn)" /tmp/viblio-api-0.4.0.zip
chmod 600 /tmp/viblio-api-0.4.0.zip
python3 ~/atualizar-viblio.py --zip /tmp/viblio-api-0.4.0.zip --sha256 SHA256_PUBLICADO_COM_ESTE_ZIP
```

O SHA-256 exato acompanha o arquivo nos anexos da versão e em `downloads/SHA256SUMS`. Não reutilize o hash de outra versão. O atualizador fica em `~/atualizar-viblio.py`, fora da pasta da API. Se ainda não existir, a API anterior inclui `scripts/atualizar-viblio.py`; copie-o para `~/atualizar-viblio.py` antes de rodar.

O atualizador confere o ZIP, faz backup do PostgreSQL, capas/snapshots privados e `.env`, confirma o histórico das migrações, aplica as novas e reconstrói somente a API. Projeto `viblio-catalog-api`, serviço `viblio-api`, volume e `.env` são preservados. Não rode `configure.py` de novo, `down -v` ou substitua `/opt/viblio/compose.yaml`.

## Conferir

```bash
cd /opt/viblio/api
ls -l migrations/008_activity_feed.sql migrations/009_book_clubs.sql
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl -fsS http://127.0.0.1:8088/v1/auth/info
```

Deve ficar `healthy`; o JSON público de auth/info inclui `feed_api: 1` e `clubs_api: 1`. O healthcheck autenticado agora verifica também o esquema novo. Confira `https://api.viblio.com.br/v1/auth/info` usando dados móveis sem VPN. Não envie tokens, `.env` ou senhas.

Instale o APK por cima do existente, sem desinstalar ou limpar dados. Amigos → Feed → Compartilhar minhas leituras é individual e começa desligado. Só atividades novas após a ativação são compartilhadas com amizades aceitas. Clubes funcionam entre pessoas convidadas que aceitaram o convite; não tornam suas estantes privadas públicas.

Em Clubes, crie um grupo e adicione participantes. Cada clube tem seus próprios admins. Promover mantém sua administração; Transferir minha administração promove a pessoa e transforma você em membro na mesma transação. Antes de sair como último admin, promova alguém ou exclua o clube com confirmação.

Os membros indicam um livro já sincronizado da própria estante e votam. Admin escolhe um dos mais votados e o prazo. Etapas definem página e data de discussão. Páginas de uma edição vinculada compatível acompanham a sincronização; Atualizar minha página permite informar progresso exclusivo do clube, sem mudar o livro da estante. Admins ajustam datas manualmente, com opção de mover discussões futuras junto. O app não muda datas sozinho. As ações sociais exigem conexão; leituras pessoais continuam podendo sincronizar depois de uso offline.

O APK também permite editar data/duração e apagar sessões no histórico. A página atual é preservada; o tempo e as médias são recalculados. Essas alterações usam a sincronização privada existente, e esta API atualiza a duração/total publicados no feed sem duplicar a sessão.
