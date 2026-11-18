# Viblio 0.2.2 — entrada, sincronização e proteções

## Entrega

APK release, `br.com.viblio`, versão 0.2.2/code 14, Android 5+ para modo local e Android 6+ para contas/Keystore. Depuração desativada. Assinatura de atualização preservada: SHA-256 `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`. Permissões: Internet e estado da rede. O servidor pessoal não foi acessado; não há novo APK da Central.

Entrada responsiva com largura máxima em telas grandes, logo da Estante, formulário e ações diferenciadas, servidor recolhido quando salvo, senha oculta por padrão com mostrar/ocultar, preenchimento por gerenciadores de senha, cadastro por convite e recuperação. A configuração da primeira conta é secundária. Sessão salva abre a Estante; Conta mantém perfil, saída e administração. [Captura real no emulador](../downloads/preview-viblio-login-0.2.2.png), com servidor ainda não configurado.

JSON manual no Viblio exige administrador tanto no menu como no retorno do seletor de arquivos, com nova checagem na execução. A sincronização privada continua disponível a cada titular da própria Estante. O comportamento padrão da Central foi preservado.

A reabertura de um perfil conhecido mostra a cópia local e confere alterações em segundo plano. Revisão/hash inalterados não reenviam o acervo. Com conexão restabelecida e app aberto, mudanças locais pendentes são enviadas; mudanças remotas sem conflito são combinadas automaticamente. Antes de aplicar o resultado é conferido novamente se a cópia local mudou, se há sessão ativa, formulário, organização manual ou arquivo em processamento. Conflitos no mesmo campo continuam pedindo decisão. Sessão revogada oculta o perfil privado sem apagar a cópia local.

API: cotas por IP direto e por conta autenticada, buckets limitados, restrição de requisições simultâneas, corpos pequenos para formulários e tempo limitado para leitura do corpo. Erros de validação não repetem a senha enviada. Respostas privadas não são armazenáveis em cache. Veja as cotas e o alcance em [SECURITY.md](../viblio-api/SECURITY.md).

## Verificação concluída

- Build debug e release, instrumentação compilada, lint debug/release sem erros; avisos de depreciação/estilo permanecem. Compilação Kotlin da Central passou com o código compartilhado.
- 104 testes unitários do Viblio: zero falhas, erros ou ignorados.
- 29 testes da API em PostgreSQL 17 descartável: zero falhas. Cobrem isolamento, convites, credenciais, arquivos, sincronização/mesclagem/conflitos, autorização, limites atômicos por conta/IP, concorrência, renovação dos buckets e ausência de credenciais nos erros. Uma advertência de depreciação da biblioteca de testes.
- Docker da API construído com as dependências congeladas existentes e instalação pelos 20 wheels locais.
- Rodada nativa completa no **APK release entregue**, em emulador Android API 28, passou contra API/PostgreSQL isolados: formulários, senha inicialmente oculta e revelada apenas por ação, cadastro por convite, primeira vinculação, login/saída/reabertura, perfil privado, JSON recusado a convidado, sincronização de progresso e combinação automática com nota alterada remotamente, preservação após reabrir e revogação de sessão no servidor ocultando o perfil sem apagar seus livros.
- Verificações nativas anteriores da coleção também passaram: imagens HTTP/decodificação/cache, pesquisa/preenchimento/capa local, SQLite, ordem manual, bloqueio de substituição durante organização, notas, sessões, remoção/restauração, IDs grandes e JPEG preservado.
- O harness imprime a saída mesmo se o comando de instrumentação falhar. Foi usada a opção de limpar apenas os dados do emulador descartável antes da rodada final; há guardas que recusam telefones pessoais. A política temporária de APIs ocultas usada pelo teste de arraste foi restaurada para `null`.
- APK entregue comparado ao artefato release testado; manifesto e certificado conferidos. ZIP com CRC íntegro, 20 wheels, migração 005, guia e fontes finais correspondentes; sem `.env`, dumps ou dados pessoais.

## Limites e atualização

Atualize pelo [roteiro Nextcloud → backup → extração → migração existente → rebuild → healthy](../viblio-api/UPDATE_0_2_2.md), depois instale o APK por cima sem desinstalar. Não há migração nova nesta versão; a 005 precisa estar aplicada. Dados, IDs, capas e contas são mantidos. A instalação real no Debian ainda depende de executar o guia.

Sincronização automática funciona com o app aberto, normalmente após cinco segundos de alterações e na conferência periódica de um minuto; falhas têm espera antes de repetir, e mudanças conflitantes aguardam decisão. Com o app fechado, o envio ocorre ao reabrir. Mudanças ainda usam snapshots completos com capas; transferência incremental de arquivos/registros não foi implementada. Pode aparecer um indicador breve ao aplicar alterações recebidas para impedir edição concorrente.

HTTPS público, domínio, WAF/CDN, proteção do servidor de origem e configuração externa não foram implantados por este pacote. Cotas em memória valem por processo e reiniciam com ele; a implantação continua com um worker. Há também limites persistentes de tentativas de autenticação. Não houve auditoria independente ou teste de carga da conexão residencial.

## Artefatos

| Arquivo | Bytes | SHA-256 |
| --- | ---: | --- |
| viblio-0.2.2.apk | 1884868 | `130bb7ef917973e80f9b4ec22fed1128208a0ea35ce88a32d52016e45accd994` |
| viblio-api-0.2.2.zip | 15987215 | `019169dc2db48591cb873f98f36cd31c19d05fa04bc59c71d253b697587f0c3d` |
