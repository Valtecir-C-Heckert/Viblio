# Atualizar Viblio para contas por convite — 0.2.0

Instale a API nova antes de habilitar contas no APK. A atualização mantém o catálogo, as capas e a configuração existentes. Não migra contas do Valteca antigo. O servidor pessoal não foi acessado durante o desenvolvimento.

## 1. Backup

No celular, exporte o JSON em **Estante → ⋮ → Backup / importar** e guarde fora do aplicativo. Não desinstale o Viblio.

Antes de extrair o novo pacote, no terminal do servidor execute:

```bash
(
  set -eu
  umask 077
  cd /opt/viblio/api
  viblio_backup="/opt/viblio/backups/before-accounts-$(date +%Y%m%d-%H%M%S)"
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

O bloco para apenas a API durante a cópia e a inicia ao terminar, inclusive em falha. O PostgreSQL permanece ligado. Continue somente se terminar sem erro. O backup contém dados privados: mantenha acesso restrito e copie para outro disco. Não compartilhe `.env` ou dump.

## 2. API

Extraia **viblio-api-0.2.0.zip** sobre **/opt/viblio/api**, preservando `.env`. Não rode novamente `scripts/configure.py`, não substitua `/opt/viblio/compose.yaml` e não troque a chave.

Para quem já usa a API 0.1.8 com capas alternativas, execute um comando por vez:

```bash
cd /opt/viblio/api
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/004_accounts.sql
```

Se a migração terminar sem erro:

```bash
docker compose -p viblio-catalog-api -f compose.api.yaml up -d --build
docker compose -p viblio-catalog-api -f compose.api.yaml ps
```

Aguarde **healthy**. Se sua API for anterior à 0.1.8, aplique antes a migração 003, conforme `UPDATE_0_1_8.md`. A migração 004 é aditiva, repetível e não altera os registros existentes do catálogo.

Preserve o projeto `viblio-catalog-api`, a rede `viblio_default` e o volume `viblio-catalog-api_viblio-covers`. Não use `down -v`. O ZIP inclui as mesmas dependências Linux x86_64/Python 3.12; a imagem base Docker ainda precisa estar disponível.

Em caso de falha, consulte os logs localmente:

```bash
docker compose -p viblio-catalog-api -f compose.api.yaml logs --tail=50 viblio-api
```

## 3. Primeiro administrador e vinculação

1. Instale **viblio-0.2.0.apk** por cima do Viblio atual, sem desinstalar.
2. Abra **Conta → Criar conta por convite**. Informe endereço da API, e-mail e senha de pelo menos 12 caracteres. Na primeira conta, pode deixar convite vazio: o app usa a chave da instalação já configurada ou a chave informada no campo opcional. Ela autoriza somente o primeiro administrador. Não a compartilhe.
3. Confirme **Vincular minha Estante** para associar o acervo atual. Livros, volumes, capas, notas, histórico, livro atual e ordem manual são preservados. Também pode decidir depois ou começar vazia em perfil separado.
4. Conclua **Sincronizar**. Se já houver acervo nessa conta, escolha baixar ou combinar. Conflitos no mesmo campo pedem escolha. Antes de sincronizar ou restaurar, pause a leitura e salve a página.
5. Em **Conta → Gerar convite**, crie um código para cada pessoa do teste. Compartilhe somente o endereço da API e o convite. Cada convite funciona uma vez e vence em sete dias por padrão; pode ser revogado.

Convidados não precisam da chave da instalação, senha PostgreSQL ou acesso ao catálogo por conexão direta ao banco. Precisam alcançar a API pela rede local/Tailscale. Convite não concede acesso à VPN. Para endereço público, use HTTPS.

## Uso e recuperação

A Estante funciona offline. Alterações locais são enviadas após cinco segundos durante o uso; o app confere o servidor a cada minuto. Mudanças de outro aparelho pedem **Sincronizar** para combinar antes de aplicar. Não há serviço permanente com o app fechado. Sair esconde o perfil; entrar novamente reabre seus dados. Cada perfil tem banco, capas e preferências próprios. O HA precisa de pareamento próprio por perfil e não entra no backup da conta.

**Remover da Estante** arquiva um livro/volume/título, preservando nota e histórico em **Conta → Removidos da Estante**. Ali pode restaurar ou excluir seus dados definitivamente, com confirmação. O catálogo compartilhado e outras contas não mudam. Backups antigos podem conter o item até vencer a retenção.

**Conta → Backups e restauração** lista dez versões completas. Restaurar guarda uma cópia local de segurança antes de substituir os dados e pede sincronização para publicar a restauração. Há também três cópias locais de segurança por perfil, no armazenamento privado do app. Elas não substituem backup externo do servidor.

Não há e-mail automático nem validação de propriedade do e-mail nesta beta. O administrador pode gerar um código de recuperação para um convidado: dura 24 horas e funciona uma vez. Recuperação troca a senha e revoga as sessões antigas. Para recuperar o próprio administrador, no terminal confiável do servidor:

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml exec viblio-api python -m app.reset_admin
```

O comando pede o e-mail de um administrador existente e a nova senha, sem exibi-la. Não cria outra conta nem apaga a biblioteca.

## Limites

Até 32 MiB por backup completo descompactado, incluindo capas; 24 MiB comprimido. Até 5.000 livros/volumes, 5.000 títulos, 20.000 sessões e 100.000 datas. Se exceder, a operação é recusada com dados locais preservados; não corta registros. O servidor mantém dez backups completos e mil bases compactas para combinar aparelhos offline. Uma origem mais antiga pede revisão como nova importação; confira exclusões definitivas que ainda podem existir no servidor.

Após ativar contas, livros e capas pessoais não são publicados automaticamente no catálogo compartilhado. Convidados continuam pesquisando nele; cadastros manuais ficam no acervo pessoal.

O APK exporta JSON esquema 6 e importa versões 1 a 6. A Central não recebe APK nesta entrega e sua versão antiga pode recusar JSON 6. Os aplicativos continuam separados. Guarde banco, volume de capas (inclui `catalog-id` e backups privados) e `.env` juntos nos backups do servidor.
