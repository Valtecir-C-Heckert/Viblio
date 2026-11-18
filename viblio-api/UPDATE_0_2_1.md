# Viblio 0.2.1 — nome de usuário e entrada inicial

Atualize a API antes de instalar o APK 0.2.1. As contas, IDs, senhas, sessões e bibliotecas da 0.2.0 são mantidos. O servidor pessoal não foi alterado pelo desenvolvimento.

1. Faça backup conforme a seção 1 de [UPDATE_0_2_0.md](UPDATE_0_2_0.md): banco, volume e `.env` protegidos.
2. Coloque `viblio-api-0.2.1.zip` no servidor e extraia sobre `/opt/viblio/api`, preservando `.env`. Não execute novamente `scripts/configure.py` nem substitua `/opt/viblio/compose.yaml`.
3. No terminal, um comando por vez:

```bash
cd /opt/viblio/api
docker exec -i viblio-postgres psql -v ON_ERROR_STOP=1 -U viblio -d viblio < migrations/005_usernames.sql
```

Se terminar sem erro:

```bash
docker compose -p viblio-catalog-api -f compose.api.yaml up -d --build
docker compose -p viblio-catalog-api -f compose.api.yaml ps
```

Aguarde `healthy`. Preserve projeto `viblio-catalog-api`, rede `viblio_default` e volume `viblio-catalog-api_viblio-covers`. Não use `down -v`. Se sua instalação for anterior à 0.2.0, siga primeiro as migrações 003 e 004 do guia anterior. A migração 005 só acrescenta a coluna opcional `username` e um índice único sem distinguir maiúsculas; pode ser repetida.

4. Instale `viblio-0.2.1.apk` por cima, sem desinstalar. Sua sessão já existente continua abrindo diretamente a Estante.
5. Como sua conta foi criada antes do nome de usuário, abra **Conta → Definir nome de usuário**. Escolha de 3 a 32 letras/números/ponto/sublinhado, começando com letra ou número. O nome é salvo em minúsculas e precisa estar disponível. Ele não muda o ID da conta ou sua Estante. Pode continuar entrando com e-mail.

Novos cadastros pedem nome de usuário, e-mail, senha e convite. O formulário de entrada aparece ao iniciar sem sessão e depois de sair; não fica dentro da aba Conta. Quem já está conectado entra direto. A opção **Usar Estante offline** preserva o uso local sem conta; nessa sessão a Conta mostra modo local, sem formulário de login. Reabra o aplicativo para entrar. Sair volta à tela inicial de entrada.

A aba Conta mostra os dados do perfil, edição do nome e saída, mantendo sincronização, backups e convites de administrador. O botão Home Assistant foi removido da interface. Pareamentos anteriores permanecem privados por perfil; convidados não recebem suas credenciais, nem um controle para configurá-lo. A Central não recebe mudanças.

Esta atualização não configura DNS, HTTPS ou acesso público ao servidor. Enquanto isso não for feito, o endereço continua precisando estar acessível pela rede já configurada. Não basta distribuir o convite para contornar esse requisito. Não compartilhe chave de instalação nem senha do PostgreSQL.
