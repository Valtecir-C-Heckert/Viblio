# Viblio: roteiro completo para liberar convidados

Este roteiro prepara o acesso público **apenas à API do Viblio**, usando Cloudflare Tunnel. Amigos não precisam de Tailscale nem de senha do PostgreSQL. O catálogo é compartilhado; contas e estantes continuam privadas. O convite autoriza **criar uma conta**; quem já tem conta usa Entrar.

**Estado da entrega 0.2.8:** o proprietário confirmou em 01/11/2026 o JSON de `https://api.viblio.com.br/v1/auth/info` em dados móveis, com Wi-Fi e Tailscale desligados, e a API saudável após ajustar PUBLIC_BASE_URL. O APK 0.2.8 usa esse endereço por padrão. O roteiro abaixo permanece como referência de implantação e retorno seguro; não repita a criação de conta/banco se já concluiu essas etapas.

Os comandos são para a sessão SSH de `valtecir@srvValtecir`. No computador, entre pelo endereço que você já usa. O endereço escolhido é **`https://api.viblio.com.br`**. Na etapa 1 precisamos confirmar o controle de `viblio.com.br` para configurar o DNS.

## 1. Conferir domínio e guardar o DNS atual

1. Entre no painel onde registrou o domínio, como Registro.br.
2. Confirme que o domínio está em sua conta e não está vencido.
3. Guarde os registros DNS atuais e os servidores DNS, incluindo registros de e-mail MX/TXT. Não apague registros de outros serviços.
4. Adicione esse domínio à sua conta Cloudflare, no plano gratuito, e revise a importação dos registros. Ative autenticação em dois fatores na conta Cloudflare.
5. Caso o domínio ainda não use o DNS da Cloudflare, no registrador substitua os servidores DNS pelos dois que a Cloudflare informar. Aguarde o domínio aparecer como **Active/Ativo**. Essa alteração exige controle do domínio e pode demorar; não prossiga antes de resolver eventuais diferenças dos registros.

Não é necessário transferir a propriedade do domínio para a Cloudflare. Domínio e renovação continuam tendo os custos do seu registrador.

## 2. Confirmar que a API atual está funcionando

No servidor:

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl -fsS http://127.0.0.1:8088/v1/auth/info
```

O container precisa estar **healthy**. A resposta precisa ser JSON com `initialized: true`, `username_login: true` e `server_id`. Guarde apenas o `server_id` para comparar depois; não contém senha. Se `initialized` for falso, pare e conclua a primeira conta administradora pela conexão privada existente. Não inicialize outro banco.

Se localhost:8088 não abrir, confira o bind e a porta atuais antes de criar o túnel. Não altere DATABASE_URL, a chave da instalação ou o projeto Compose para resolver isso por tentativa.

## 3. Fazer backup antes de mudar a URL

```bash
sudo install -d -m 700 -o valtecir -g valtecir /opt/viblio/backups /opt/viblio/tunnel
umask 077
VIBLIO_PUBLIC_BACKUP="/opt/viblio/backups/antes-publico-$(date +%Y%m%d-%H%M%S)"
mkdir "$VIBLIO_PUBLIC_BACKUP"
docker exec viblio-postgres pg_dump -U viblio -d viblio -Fc > "$VIBLIO_PUBLIC_BACKUP/database.dump"
cp -p /opt/viblio/api/.env "$VIBLIO_PUBLIC_BACKUP/.env"
chmod 600 "$VIBLIO_PUBLIC_BACKUP/.env"
docker volume inspect viblio-catalog-api_viblio-covers
```

Confirme que os comandos deram certo e que o volume existe. **Não continue se houver erro.** Depois faça a cópia do volume inteiro, que inclui capas e arquivos privados do servidor:

```bash
docker run --rm --mount type=volume,source=viblio-catalog-api_viblio-covers,target=/data,readonly --mount "type=bind,source=$VIBLIO_PUBLIC_BACKUP,target=/backup" alpine:3.22 tar -czf /backup/covers-and-private.tar.gz -C /data .
sudo chown valtecir:valtecir "$VIBLIO_PUBLIC_BACKUP/covers-and-private.tar.gz"
chmod 600 "$VIBLIO_PUBLIC_BACKUP/covers-and-private.tar.gz"
ls -lh "$VIBLIO_PUBLIC_BACKUP/database.dump" "$VIBLIO_PUBLIC_BACKUP/covers-and-private.tar.gz"
```

Os dois arquivos precisam ter tamanho maior que zero. Guarde o caminho dessa pasta. Se desconectar do SSH, defina novamente `VIBLIO_PUBLIC_BACKUP` com o caminho já criado. Esse backup fica fora da pasta usada para construir a imagem da API; nunca envie `.env`, dumps ou tokens ao GitHub/chat.

## 4. Criar um túnel na Cloudflare

1. No painel **Cloudflare One/Zero Trust**, procure **Networks/Rede → Tunnels/Túneis** ou **Connectors → Cloudflare Tunnels**, conforme a interface.
2. Crie um túnel do tipo **Cloudflared**, gerenciado pelo painel, com o nome `viblio-api`.
3. Escolha Docker para visualizar a instalação. Não execute o comando inteiro do painel; vamos usar a configuração abaixo para não deixar o token no histórico/argumentos do processo.
4. Copie **somente o token** exibido depois de `--token`. Ele é uma credencial: não envie por mensagem ou publique.

No servidor, grave o token com entrada oculta:

```bash
cd /opt/viblio/tunnel
umask 077
read -r -s -p 'Cole SOMENTE o token do túnel e pressione Enter: ' VIBLIO_TUNNEL_TOKEN
printf '\n'
printf '%s' "$VIBLIO_TUNNEL_TOKEN" > tunnel-token
unset VIBLIO_TUNNEL_TOKEN
chmod 600 tunnel-token
printf 'VIBLIO_TUNNEL_UID=%s\nVIBLIO_TUNNEL_GID=%s\n' "$(id -u)" "$(id -g)" > .env
```

Não é preciso imprimir o token para conferir. Se vazar, revogue/gere outro no painel antes de prosseguir.

Crie o arquivo:

```bash
nano /opt/viblio/tunnel/compose.tunnel.yaml
```

Cole o conteúdo abaixo. Para salvar: Ctrl+O, Enter; para sair: Ctrl+X.

```yaml
services:
  tunnel:
    image: cloudflare/cloudflared@sha256:4955524aeaa379e154023d5a027c250f7b35041a2e7e950cf13f144bc9b9c5ba
    restart: unless-stopped
    network_mode: host
    user: "${VIBLIO_TUNNEL_UID:?Informe o UID em .env}:${VIBLIO_TUNNEL_GID:?Informe o GID em .env}"
    command: ["tunnel", "--no-autoupdate", "run", "--token-file", "/run/secrets/tunnel-token"]
    volumes:
      - ./tunnel-token:/run/secrets/tunnel-token:ro
    read_only: true
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    mem_limit: 256m
```

Essa imagem oficial está fixada pelo digest. A versão 2026.10.7 e a opção `--token-file` foram verificadas em container descartável durante a preparação. Use a configuração no Debian/Linux; o modo host faz `localhost` apontar para o próprio servidor.

Suba **somente o túnel**, em projeto separado:

```bash
cd /opt/viblio/tunnel
docker compose -p viblio-public-tunnel -f compose.tunnel.yaml config --quiet
docker compose -p viblio-public-tunnel -f compose.tunnel.yaml up -d
docker compose -p viblio-public-tunnel -f compose.tunnel.yaml ps
docker compose -p viblio-public-tunnel -f compose.tunnel.yaml logs --tail 30
```

O conector deve aparecer conectado/Healthy no painel Cloudflare. Esse container não precisa de portas publicadas. Não instale Home Assistant, banco ou Nextcloud novamente.

## 5. Publicar o hostname da API

No túnel criado, adicione uma **Published application route/Public hostname**:

- Subdomínio: `api`.
- Domínio: `viblio.com.br`, ou o domínio que você controla.
- Caminho/Path: `/v1/.*` (expressão para as rotas da API).
- Tipo do serviço: **HTTP**.
- URL do serviço: `localhost:8088`.

Salve. A Cloudflare normalmente cria o DNS do hostname junto com essa rota; se já houver um registro com o mesmo nome, confira o serviço existente em vez de apagá-lo. Outros hostnames devem continuar sem rotas neste túnel. A raiz `/` pode dar 404, o que é esperado quando só `/v1/` está publicado.

O celular usa **HTTPS** até a Cloudflare; o conector usa o túnel criptografado e acessa a API local. Não precisa abrir 8088, 5432, 80 ou 443 no roteador para esse fluxo. Não publique portas do PostgreSQL, pgAdmin, CasaOS ou HA.

Na Cloudflare, para esse hostname:

- Crie uma regra de cache **Bypass cache** para todas as requisições a `api.viblio.com.br`; não use Cache Everything nas rotas de conta/estante.
- Não coloque a API atrás de uma tela de login do Cloudflare Access ou desafio JavaScript permanente: o APK usa sua própria autenticação e não possui um navegador para resolver esses desafios.
- Mantenha as proteções de tráfego disponíveis no plano. Isso não significa WAF completo ou autoescalonamento do seu Debian.

## 6. Testar sem depender de Tailscale

No servidor:

```bash
curl -fsS https://api.viblio.com.br/v1/auth/info
```

O `server_id` precisa ser **o mesmo** da etapa 2 e `initialized` precisa continuar verdadeiro. Se mudar, pare: a rota pode estar apontando para outra instalação.

No celular, desligue o Wi-Fi, use os dados móveis e **desconecte o Tailscale**. Abra no navegador:

```text
https://api.viblio.com.br/v1/auth/info
```

Precisa aparecer JSON sem tela de login da Cloudflare e sem alerta de certificado. Esse é o teste que comprova acesso externo. Não use `curl -k`, HTTP público ou desative validação de certificado para passar no teste.

## 7. Mudar apenas a URL pública usada pelas capas da API

Mesmo com o túnel funcionando, uploads antigos/novos podem apontar para IP privado se `PUBLIC_BASE_URL` continuar local. Precisamos atualizar **somente essa variável**, preservando DATABASE_URL, chave e volumes.

Opção direta, no servidor:

```bash
nano /opt/viblio/api/.env
```

Troque apenas a linha existente para:

```dotenv
PUBLIC_BASE_URL=https://api.viblio.com.br
```

Não copie o restante do arquivo para o chat. Não rode `configure.py` de novo.

Alternativamente, o helper versionado `viblio-api/deploy/public/definir-url-publica.py`, depois de copiado para o servidor, faz essa alteração com backup privado adicional e validação da origem HTTPS:

```bash
python3 /opt/viblio/api/deploy/public/definir-url-publica.py --url https://api.viblio.com.br --backup-dir "$VIBLIO_PUBLIC_BACKUP"
```

Use essa alternativa somente se o arquivo realmente existir no servidor. O caminho não é criado automaticamente por este guia; editar com nano é suficiente.

Recrie a API com o mesmo projeto:

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml up -d --build
docker compose -p viblio-catalog-api -f compose.api.yaml ps
curl -fsS https://api.viblio.com.br/v1/auth/info
```

Aguarde ficar **healthy**. Essa configuração não exige migração PostgreSQL. Não use `down -v`, não substitua `/opt/viblio/compose.yaml` e não troque o nome do projeto ou volume.

URLs externas de provedores de capas continuam dependendo desses provedores. Capas locais já salvas continuam no celular; esta etapa ajusta os links gerados pelo servidor.

## 8. Gerar o APK público com o endereço definitivo

Depois das etapas 6 e 7, instale o APK 0.2.8 pelo anexo em Releases. Em uma futura troca de endereço, confirme primeiro o JSON pelos dados móveis sem Tailscale. Não envie token do túnel, senha do banco ou chave da API.

O build já usa `https://api.viblio.com.br` por padrão. `VIBLIO_PUBLIC_API_URL` ou `-PviblioPublicApiUrl` permite uma substituição explícita para testes/desenvolvimento. O APK público pode esconder o campo Servidor e a configuração da primeira conta. O endereço não vem de um convite. Credenciais e chaves não entram no APK.

A versão 0.2.8 foi compilada com o endereço definitivo, após o teste externo confirmado pelo proprietário. A versão 0.2.7 não muda de endereço sozinha: é necessário atualizar o APK e fazer um novo login para trocar uma sessão antiga.

Contas antigas mantêm a sessão e o endereço salvo até sair; ao entrar novamente no APK público, o endereço padrão será usado. Como é o mesmo servidor/banco (`server_id`), o perfil da mesma conta pode ser reaberto sem trocar IDs. Uma URL nova não migra dados para outro banco.

## 9. Gerar e usar um convite

Com o APK final instalado, na sua conta administradora: **Conta → Gerar convite → Copiar código**. Envie o APK e esse código à pessoa, em canal privado. Não envie senha do PostgreSQL, chave da instalação ou credenciais pessoais.

O convidado:

1. Instala o APK.
2. Escolhe **Criar conta por convite**.
3. Informa nome de usuário, e-mail, senha e código.
4. Se já tem uma estante local sem conta, confirma **Usar minha estante atual** para preservar livros/volumes, capas, notas, progresso e histórico; pode começar vazia ou decidir depois.
5. Aguarda a primeira sincronização. Não usa JSON.

Quem já tem conta usa **Entrar**, sem novo convite. Se a conta já tiver uma estante no servidor e houver outra local, a combinação exige escolhas em conflitos; não promete eliminar duplicatas automaticamente. Dados de outra conta já vinculada não podem ser apropriados por um novo usuário.

## 10. Teste antes de convidar mais pessoas

Faça primeiro com uma conta de teste em outro aparelho:

- Sem Wi-Fi de casa e sem Tailscale, cadastrar por convite e pesquisar um título no catálogo.
- Ver dados e capa; adicionar à estante do convidado e confirmar que sua conta não ganhou esse livro.
- Sair e entrar sem convite; conferir notas, progresso e histórico preservados.
- Desconectar a internet, alterar uma leitura e reconectar com o app aberto; conferir sincronização.
- Confirmar que reutilizar o mesmo convite é recusado e que um convite revogado não cadastra ninguém.

O servidor precisa estar ligado para cadastro, login, pesquisa e sincronização. A estante já carregada fica no aparelho. Esta etapa não fornece um backend sempre disponível na nuvem nem sincronização com o aplicativo fechado.

## Problemas comuns e retorno seguro

- **1033/conector offline:** confira o container do túnel e o token no servidor. Não poste o token. Se a rede bloqueia tráfego de saída, o conector precisa alcançar a Cloudflare pela porta 7844 TCP/UDP; isso não exige abrir uma porta de entrada no roteador.
- **502:** o túnel conectou, mas não alcança `localhost:8088`; repita o curl local da etapa 2 e confira a porta/bind.
- **JSON local funciona, público não:** confira DNS, hostname e path do túnel. Não use URL do Nextcloud ou do HA.
- **HTML/desafio em vez de JSON:** revise a política do Cloudflare Access/desafios para esse hostname.
- **Capas privadas não abrem fora:** confira PUBLIC_BASE_URL e teste uma nova sincronização/login pela URL pública.
- **429:** os limites continuam ativos; não desligue a proteção. A identificação de IP através de proxy precisa ser confiável. Não configure confiança irrestrita em X-Forwarded-For; contas também têm limites próprios.

Para retirar o acesso público sem apagar dados:

```bash
cd /opt/viblio/tunnel
docker compose -p viblio-public-tunnel -f compose.tunnel.yaml stop
```

Se precisar reverter PUBLIC_BASE_URL, copie **somente** o `.env` guardado na etapa 3 para `/opt/viblio/api/.env` e repita o `up -d --build` da API com o mesmo projeto. Esta alteração não precisa restaurar o banco. Não restaure um dump por cima de contas/leituras novas sem avaliar as mudanças posteriores ao backup.
