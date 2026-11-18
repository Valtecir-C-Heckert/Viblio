# Proteções e limites — 0.2.2

O servidor valida a sessão e a conta em cada acesso privado. Convites, recuperação e administração continuam com autorização no backend; a chave de instalação não lê Estantes pessoais. Senhas usam scrypt; tokens e códigos são armazenados por hash. O app conserva credenciais no Android Keystore, mantém perfis separados e não inclui dados pessoais no backup do Android.

## Admissão e tamanho

Na implantação documentada, um worker aplica contadores em memória por janela de um minuto:

| Identidade | Grupo | Limite/minuto |
| --- | --- | --- |
| IP direto | Autenticação | 120 |
| IP direto | Sincronização/envio de capa | 60 |
| IP direto | Demais leituras | 600 |
| Conta validada | Sincronização | 20 |
| Conta validada | Leituras da Estante | 60 |
| Conta validada | Catálogo | 120 |
| Conta validada | Demais operações da conta | 60 |

Contadores por conta são compartilhados entre suas sessões. Há ainda os limites persistentes de autenticação por identificador e IP, de 12 e 120 tentativas por ação a cada 15 minutos. Excesso retorna 429 com Retry-After. Não usamos X-Forwarded-For como identidade fornecida pelo cliente; o Docker inicia Uvicorn sem proxy headers. Atrás de um proxy os usuários compartilham também a cota do IP desse proxy. Não habilite confiança irrestrita em cabeçalhos para contornar isso.

Máximo de 16 requisições simultâneas, sendo até quatro envios/sincronizações e quatro operações de autenticação. Excesso retorna 503, com Retry-After. Os buckets em memória são limitados a 8.192 entradas; reiniciar o processo limpa esses contadores, e entradas antigas podem ser descartadas ao atingir o teto. Os limites persistentes de autenticação permanecem. Isso reduz abuso comum; não substitui proteção de rede contra DDoS. Mais workers/réplicas exigem cotas compartilhadas antes de escalar.

Corpos de autenticação/configuração/leitura: até 16 KiB. Multipart de capa: limite anterior de 12 MiB + 64 KiB; imagem individual até 12 MiB, verificada e convertida em JPEG até 2048 px. Sync gzip: até 24 MiB comprimidos e 32 MiB descomprimidos, com validação do conteúdo. A leitura do corpo admite até 60 s totais e 15 s sem receber outro fragmento. Respostas privadas, erros tratados e autenticação têm Cache-Control: no-store; nosniff e no-referrer também são enviados. Arquivos públicos de capa continuam públicos; snapshots privados não são servidos em /covers.

## APK e sincronização

O APK entregue usa build release com depuração desativada e mantém o certificado da instalação existente para permitir atualização. Ainda é uma versão de testes por convite; não é uma publicação em loja.

Importar/exportar JSON pela interface e pelo retorno do seletor de arquivos exige sessão administradora no Viblio. É uma restrição do recurso local, não uma garantia contra extração do próprio acervo de um telefone modificado. O protocolo privado de sincronização continua disponível a cada titular exclusivamente para seu perfil, com autenticação no servidor.

O app compara revisão e hash antes de transmitir um acervo inalterado. Alterações offline pendentes são combinadas automaticamente com o servidor enquanto o app está aberto, ao voltar a rede ou na verificação periódica. Mudanças recebidas só substituem a cópia local depois de nova verificação de hash, sem formulário/sessão ativa e com backup. Conflitos no mesmo campo exigem escolha. O app fechado não tem serviço de envio permanente. Nesta versão, alterações ainda usam snapshots completos com capas: transferência incremental de arquivos e registros é uma etapa posterior.

## Antes de acesso público

Ainda é necessário configurar DNS, HTTPS e perímetro no servidor. Não exponha PostgreSQL, pgAdmin ou a chave de instalação a convidados. Use proxy/túnel com origem acessível somente por ele; imponha limites de tamanho/tempo também no proxy. Um WAF/CDN pode absorver tráfego antes de chegar à residência, mas não foi contratado nem configurado por este pacote. Exclua /v1 e respostas autenticadas de qualquer cache compartilhado. Cotas devem considerar a identidade autenticada na aplicação e o IP validado pelo perímetro. Não exponha diretamente a porta 8088 à Internet.

Backups do banco, volume e .env contêm dados privados: armazenamento restrito e cópia externa protegida são necessários. Nenhuma proteção elimina todo risco; não houve auditoria independente ou teste de carga da rede residencial.

## Amigos — API 0.2.13

Amizade exige aceite explícito do destinatário; convite de cadastro e chave de instalação não concedem acesso. Usuários são localizados apenas por nome exato, com requisições autenticadas e limitação de taxa. Relação por par de UUIDs, única e com travas ordenadas no PostgreSQL para evitar duplicação concorrente e ultrapassagem de limites: até 200 amigos e 100 pedidos pendentes por conta.

Leitura compartilhada usa uma projeção com campos permitidos, sem e-mail, notas, avaliação, sessões, configurações ou snapshot. Livros e coleções removidos não aparecem. Página de estante até 40 itens, amigos até 30, parâmetros limitados e revisão conferida na continuação. Capas são miniaturas privadas JPEG até 160×240 e 96 KiB, sem metadados de imagem. Identificadores de livros nunca viram caminhos do sistema de arquivos.

Cada consulta e cada capa verifica sessão e amizade aceita, inclusive antes de usar cache. URLs antigas não liberam revisões removidas. Cache-Control no-store/nosniff/no-referrer também nos erros. Caches de miniaturas limitados a 256 entradas e fonte de capas a um snapshot, com os limites existentes de 24 MiB comprimidos/32 MiB descompactados. Novas requisições são recusadas após desfazer a amizade; não há URL pública para capas pessoais. Cliente mantém capas de amigos somente em memória e limpa a tela/cache ao sair, trocar conta ou receber erro de consulta.

Na implantação de um worker, cotas autenticadas separam 20 alterações de amizade/min, 60 consultas/min e 90 capas/min; porta de entrada mantém limite de concorrência e até quatro operações pesadas. Não depende de cabeçalhos de IP fornecidos pelo cliente. Isso não configura WAF/CDN/limites distribuídos fora deste serviço.

## Feed e clubes — 0.4.0

O feed é opt-in (`share_activity=false` na migração). Ativar define o marco inicial; não republica histórico anterior. Desativar remove os eventos do proprietário. Repetir um sync não duplica a mesma sessão; editar/apagar/arquivar retira ou atualiza a atividade correspondente. O horário da sessão vem do registro, inclusive offline, sujeito ao marco de consentimento, retenção de 180 dias e tolerância de cinco minutos futuros. Transições sem sessão usam updatedAt; avaliações usam o horário do recebimento por não possuírem data própria no modelo. Importar/adicionar um livro já lido não cria eventos antigos.

Máximo 512 eventos por sync e 10 mil eventos por pessoa; uma importação muito grande não é um mecanismo de publicação em massa. Feed pagina 40 eventos por cursor composto, apenas de amizades aceitas com compartilhamento ativo. Diretório autenticado pagina 40 nomes de usuário; não mostra e-mails, bibliotecas ou notas. Leitura e capas verificam novamente autorização e usam no-store. Revogação de amizade ou opt-out remove o ator dos dados retornados nos próximos pedidos; nenhum servidor consegue apagar capturas já feitas por um destinatário.

Clubes exigem convite aceito, com papéis por clube sem privilégio implícito de administrador global. Operações de membro/admin serializam no registro do clube; não é possível remover/rebaixar/sair deixando zero admins. Transferência é atômica. Votos e seleção validam a rodada e o clube; escolha exige pelo menos um voto e o maior placar. Só o próprio membro altera sua página; vinculação automática usa sua própria biblioteca e edição/páginas compatíveis. Clubes publicam somente o livro explicitamente indicado, sua miniatura, o progresso do grupo e discussões, nunca notas, metas ou a biblioteca completa. A participação não cria amizade nem acesso à estante.

Limites: 50 clubes por pessoa incluindo convites, 100 membros/convites por clube, 100 rodadas, 30 propostas/rodada, 20 etapas/rodada, 10 mil mensagens/clube, 2000 caracteres/mensagem. Histórico pagina mensagens em lotes de 40. Miniaturas copiadas são JPEG 160×240 com limite de 96 KiB. Escritas de clube 30/min por conta, leituras 60/min, admissão IP/bulk e corpos limitados pelo gate existente. Identificadores e payloads são validados; consultas usam parâmetros. Operações sociais exigem rede e não entram na fila offline de livros.

Rate limiting continua em memória, adequado à implantação documentada de um worker. Proteções distribuídas, WAF/regras Cloudflare e restrição de acesso à origem dependem de configuração no servidor/provedor; esta entrega não altera DNS, firewall ou túnel. Discussões não possuem moderação automática: autor e admins podem apagar mensagens; remoção de membro revoga seu acesso. Não há notificações push nesta versão.

Correções/exclusões do histórico usam o protocolo privado de sync existente. O feed substitui a sessão pelo mesmo event_key, remove chaves apagadas e recalcula totais das conclusões ao editar/apagar uma sessão anterior. Não interpreta apagar histórico como recuar a página pessoal.

## 0.4.1 — compartilhamento automático e livros do clube

Por instrução do proprietário, a migração 011 habilita compartilhamento para contas atuais e novas, sem confirmação/switch. Preserva o marco de quem já compartilhava e define agora para os demais; não faz backfill indiscriminado. PATCH antigo de opt-out retorna enabled=true e não apaga eventos. O feed geral continua restrito a amizades aceitas.

Membros ativos do clube podem pesquisar metadados bibliográficos dos livros ativos/sincronizados de todos os integrantes. A projeção é título/autor/capa/páginas + identidade pública do dono, sem notas, metas, avaliações ou progresso de outros livros. Pertencer ao clube não concede endpoints privados de amigos ou snapshots. Indicação valida dono/membro e livro em cada escrita; miniaturas revalidam clube/dono/revisão. Propostas publicadas permanecem no histórico ao sair o dono.

Feed de clube reúne eventos explícitos do grupo e atividades somente do livro selecionado e período da rodada, por membro ativo. Comentários de etapas fechadas não aparecem no feed. Usa paginação composta de 40 itens; candidatos também paginam em 40. Eventos de grupo retêm os 2000 mais recentes por rodada. A relação de origem do livro é separada de quem fez a indicação, evitando vincular progresso a IDs locais iguais de outra pessoa. Histórico e configuração são consultas autorizadas; ações administrativas continuam rechecando o papel no próprio clube.
