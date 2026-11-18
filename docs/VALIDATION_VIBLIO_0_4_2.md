# Viblio 0.4.2 — comentários ao vivo e carregamento de Amigos

O proprietário informou que o incidente do colega foi a conexão de Internet e
liberou a continuidade da atualização. Esta versão corrige separadamente o estado
de carregamento na troca de sessão/navegação, sem atribuir a ele o problema do colega.

## Mudanças

- Comentários das etapas recebem notificações SSE autenticadas; os dados continuam
  no PostgreSQL. Publicar e apagar notificam os participantes depois do commit.
- O app atualiza apenas a lista de mensagens, mantendo o campo, rascunho e rolagem.
  Reconecta com recuo, recupera mensagens persistidas na reconexão e pausa o fluxo
  ao consultar mensagens antigas. Navegação, troca de conta e fechamento cancelam
  a conexão, inclusive antes de o trabalho em segundo plano começar.
- A discussão verifica sessão, participação e abertura ao conectar, receber eventos
  e a cada 15 segundos. Remover participantes, alterar papéis ou fechar a discussão
  dispara nova verificação. Não há token em URL nem comentários nos eventos.
- Admissão de streams separada das requisições normais: até 32 conexões e três por
  conta. Eventos coalescidos em uma fila de tamanho um; reconexões têm quota própria.
- Criar clube fica ao lado de Atualizar. Cabeçalho separado da leitura, prazo e
  etapas usam as duas laterais, métricas de leitura têm colunas próprias e o botão
  Publicar comentário fica à direita. Removido o texto Carregando das telas sociais.
- Atualização manual conserva o conteúdo enquanto busca dados. Temas por capa
  permanecem locais ao clube e ao cartão de feed correspondente.

## Contrato de implantação

A API mantém o processo único já usado pelo Dockerfile. O barramento de eventos é
local ao processo: acrescentar workers exige um barramento compartilhado antes.
Não há migração nova nem alteração das migrações publicadas. SSE usa a mesma origem
HTTPS da conta e não segue redirecionamentos. O proxy deve transmitir os eventos sem
buffer; a resposta envia `text/event-stream`, `no-store` e `X-Accel-Buffering: no`.
A rota é `GET /v1/clubs/{clube}/stages/{etapa}/events`, com Bearer no cabeçalho.
Os eventos `ready` e `changed` levam o app a buscar o estado persistido, incluindo
exclusões; `revoked` encerra a discussão para a conta.

## Correção de carregamento

FriendsPanel invalida a geração de respostas, libera busy/refreshing e descarta
atualizações pendentes ao reiniciar uma busca. Trocar sessão fecha o cliente,
limpa dados privados e restaura o estado de carregamento antes da nova busca.
Uma resposta antiga não pode substituir a sessão/seleção atual. A seleção salva
na construção inicial continua restaurável; a de outra conta é descartada.
Endereços de sessão, credenciais e sincronização pessoal não foram alterados.

## Verificação

- API: 88 testes e 20 subtestes passaram, usando apenas PostgreSQL descartável viblio_test.
- Testes HTTP reais verificam entrega após commit, exclusão, isolamento, revogação,
  logout durante stream ocioso, concorrência e liberação de conexões.
- JVM: 160 testes passaram, incluindo protocolo, limites e cancelamento da conexão.
- Build/lint release aprovados. APK não depurável, minSdk 21, versionCode 38,
  versionName 0.4.2 e mesmo certificado da 0.4.1; sem APK novo da Central.
- Teste JVM cobre também cancelamento após cabeçalhos com corpo travado, seguido
  de uma nova chamada pelo mesmo cliente, sem bloquear a próxima busca.
- ZIP conferido pelo extrator real do atualizador; 001–011 comparadas byte a byte
  com a 0.4.1, sem configuração privada/caches/dumps. Sem migração nova.
- Imagem Docker de produção iniciada como uid 10001 com banco descartável e as
  11 migrações: health autenticado v8 e auth/info com club_events_api=1.
- APK/API e checksums em downloads; comandos de PC/servidor em
  downloads/viblio-0.4.2-servidor.txt. Atualizar API antes do APK.


- Emulador API 28 com APK release: FriendsRequestChecks confirmou nova sessão
  carregando antes da resposta antiga ser liberada; navegar durante atualização
  carregou a estante sem esperar a resposta cancelada. A suíte Friends completa
  passou, incluindo permissões, paginação, zoom, temas e navegação inferior.
- Suíte Social/CatalogEntry passou: feed por capa, principal/configurações/histórico
  do clube, comentários, indicação de livros e galeria de três colunas. Mensagem
  recebida preservou a mesma instância do campo, texto, foco e seleção do cursor.
- Capturas nativas do feed e da principal do clube inspecionadas com dados sintéticos;
  métricas, prazo, etapas, cabeçalho e botões distribuídos pelas duas laterais.

## Limites

Comentários/clubes exigem conexão. A leitura pessoal conserva a sincronização offline
existente. Este trabalho não implantou mudanças no servidor do proprietário nem
alterou DNS/túnel/WAF. A capacidade e as regras de streams descritas acima são do
serviço de um worker; escalar exige compartilhamento das notificações entre processos.
