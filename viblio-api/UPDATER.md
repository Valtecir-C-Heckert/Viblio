# Atualizador reutilizável da API

O script atualiza a instalação existente em `/opt/viblio/api`, usando o ZIP colocado nos seus arquivos do Nextcloud. Funciona com o fluxo atual do servidor Debian/CasaOS, sem alterar o APK. Não é um instalador inicial.

## Instalar uma vez

No terminal, conectado como **<usuario>**, execute:

```bash
curl --fail --location --proto '=https' --proto-redir '=https' https://raw.githubusercontent.com/Valtecir-C-Heckert/Viblio/main/downloads/atualizar-viblio.py -o ~/atualizar-viblio.py
```

O arquivo fica na sua pasta pessoal, fora da pasta que a atualização substitui. Também pode baixar pelo GitHub usando **Download raw file** e copiá-lo para essa pasta.

## Nas próximas atualizações

Envie o novo `viblio-api-VERSÃO.zip` para o seu Nextcloud, como já faz. Depois execute:

```bash
python3 ~/atualizar-viblio.py
```

Ele procura em `/DATA/AppData/nextcloud/var/www/html/data/<usuario>/files`, incluindo subpastas, e escolhe a maior versão numérica. Arquivos com sufixos como `(1)` não são selecionados; mantenha o nome original do pacote. Caso encontre cópias da mesma versão em pastas diferentes, pede que indique o caminho com `--zip`.

Se o Nextcloud exigir permissão, o script usa `sudo` somente para localizar e ler seu pacote. A senha é digitada no terminal. **Não execute o script inteiro com sudo.** A pasta `/opt/viblio/api` precisa pertencer ao usuário que o executa.

Para conferir antes, sem parar ou atualizar a API:

```bash
python3 ~/atualizar-viblio.py --check
```

Essa opção valida ZIP, checksum, configuração e lista as migrações pendentes. Cria apenas arquivos temporários e o arquivo de trava; não faz build, migrações ou backup. A execução normal ainda confere espaço, constrói a imagem e verifica a saúde real.

## O que acontece

1. Copia o ZIP para uma pasta privada em `/tmp`; limita tamanho e verifica SHA-256 com a lista publicada por HTTPS neste repositório. Sem confirmação de integridade, para.
2. Valida a estrutura antes de extrair em uma área privada de preparação. Recusa caminhos fora da pasta, links, entradas duplicadas, `.env`, arquivos criptografados e tamanhos excessivos.
3. Bloqueia atualizações simultâneas. Recusa downgrade conhecido e mudanças no conteúdo de uma versão já registrada.
4. Constrói a imagem nova com a API antiga ainda ligada. Se o build falhar, a instalação em funcionamento permanece ativa.
5. Salva o código e `.env`, pausa a API e faz backup do PostgreSQL e de **todo** o volume de capas, incluindo Estantes privadas. Confere o dump com `pg_restore --list` antes de alterar a instalação.
6. Promove os arquivos preparados, preserva `.env` e aplica migrações pendentes em ordem. O arquivo de cada migração precisa existir antes da execução. Não executa configure.py.
7. Recria somente a API usando o mesmo projeto Compose, volume e configuração. Espera até três minutos por `healthy` e confere a identidade do catálogo e a integridade do `.env`.
8. Registra a versão instalada. Repetir o mesmo comando para o mesmo pacote já instalado e saudável não refaz a atualização.

Os backups e logs ficam em `/opt/viblio/backups/before-VERSÃO-DATA-ID`, em pastas privadas. O arquivo `api.env` e o log têm acesso restrito. Banco, volume e `/opt/viblio/compose.yaml` são preservados. O script não remove automaticamente backups ou imagens anteriores; mantenha espaço livre e revise a retenção ao longo do tempo.

## Migrações

O controle fica na nova tabela técnica `public.viblio_schema_migrations`. Na **primeira execução**, como as instalações anteriores não tinham esse registro, as migrações históricas 001–005 são reaplicadas; elas são aditivas e repetíveis. Nas seguintes, o script executa somente arquivos ainda não registrados. Recusa migrações registradas que foram alteradas/removidas do pacote.

Cada release futura precisa manter as migrações anteriores intactas, acrescentar as novas e publicar o checksum do ZIP. Mudanças incompatíveis na arquitetura ou migrações que precisem de intervenção podem exigir revisão do script/roteiro. O script não se substitui automaticamente por código baixado.

## Se falhar

Se já havia parado a API, tenta reativar o código e a imagem anteriores. **Não restaura automaticamente o banco nem as capas**: isso poderia apagar dados novos. Migrações já concluídas ficam registradas; uma nova tentativa verifica o que falta. Se a migração for incompatível com a versão anterior, a reativação pode falhar e será necessário revisar o erro usando os backups.

O terminal mostra o caminho do backup e do `update.log`, sem imprimir credenciais. Não apague esse diretório antes de resolver a falha. Para confirmar o estado:

```bash
cd /opt/viblio/api
docker compose -p viblio-catalog-api -f compose.api.yaml ps
```

## Opções

Escolher uma versão já enviada:

```bash
python3 ~/atualizar-viblio.py --version 0.2.2
```

Escolher o caminho quando há cópias duplicadas:

```bash
python3 ~/atualizar-viblio.py --zip '/DATA/AppData/nextcloud/var/www/html/data/<usuario>/files/viblio-api-0.2.2.zip'
```

Sem acesso ao GitHub, informe o SHA-256 recebido da publicação confiável da versão:

```bash
python3 ~/atualizar-viblio.py --version 0.2.2 --sha256 019169dc2db48591cb873f98f36cd31c19d05fa04bc59c71d253b697587f0c3d
```

Não use um hash fornecido por um pacote desconhecido para justificar a própria integridade. O script não pede senha do banco nem token da API; usa a configuração já existente.

## Validação

16 testes unitários passaram: seleção numérica/duplicidade no Nextcloud, checksums, estrutura e extração segura, limites, histórico de migrações, preservação do `.env`, falhas no build/stop e recuperação do código anterior.

O fluxo foi executado também com Docker Compose e PostgreSQL 17 **descartáveis**, sem acessar o servidor pessoal: conferência, atualização com dump e cópia real do volume, cinco migrações históricas, repetição sem nova atualização, migração adicional, falha de saúde injetada, reativação da imagem anterior e nova tentativa bem-sucedida. Catálogo, `.env`, identidade e arquivo de Estante privada permaneceram preservados. Os checksums da publicação real 0.2.2 também foram consultados por HTTPS e conferidos. O APK e ZIP 0.2.2 já publicados não foram modificados.
