# Viblio 0.4.3 — buscas sociais e ícone

## Evidência e escopo

O vídeo fornecido mostra alternância entre Feed, Clubes e Meus amigos por cerca de
oito segundos, sem conteúdo, erro ou estado vazio concluído. O proprietário
confirmou APK 0.4.2 e que auth/info público abre JSON no navegador do colega.
Isso confirma acesso à origem, mas não comprova o resultado da busca autenticada
no app. O vídeo e os frames pessoais não entram no repositório nem no release.

## Correções

- Consultas sociais deixam de usar o prazo de 120 segundos reservado ao transporte
  de backups: leituras usam 12 segundos e escritas, 30, com limite de resposta de
  1 MB. Snapshots/sincronização pessoal conservam seus próprios limites.
- A conexão de uma busca é cancelável antes mesmo de o trabalho sair da fila.
  Trocar aba/conta, sair ou reiniciar uma busca cancela também tarefas que ainda
  não tinham chamado o HTTP. Respostas antigas continuam sujeitas aos guardas de
  geração/perfil. Uma ação do usuário cancela a atualização de fundo dos amigos.
- O botão Atualizar mostra progresso discreto enquanto a busca está pendente,
  inclusive no painel embutido Meus amigos; sem texto Carregando. Falhas exibem
  mensagem e nova tentativa. Erros de conexão identificam só o hostname usado,
  sem credenciais/tokens, ajudando a distinguir configuração e serviço.
- Listagens com HTTP 200 mas sem array items são rejeitadas, em vez de virarem
  falsamente estantes/feeds vazios. Uma resposta válida com items=[] mantém o
  estado vazio normal.
- Símbolo no ícone da tela inicial passa de 64 para 54 unidades dentro da mesma
  área de 108, aproximadamente 16% menor. Nome, pacote e assinatura são mantidos.
  A cor dinâmica do ícone principal não foi implementada: exige ícones estáticos
  pré-definidos/aliases, ou um atalho personalizado, com limitações do launcher.

## Verificação

- Compilação release e lint aprovados; 164 testes JVM passaram. Testes novos usam
  HTTP real de loopback, incluindo corpo travado, prazo de leitura, retry pelo
  mesmo cliente, resposta inválida, ACK existente e ausência de conexão quando
  um trabalho é cancelado antes da execução.
- Emulador API 28 com APK release: SocialNetworkChecks confirmou indicador durante
  resposta travada, erro após o prazo, retry bem-sucedido e estados vazios reais
  nas três abas. Com a fila bloqueada de propósito, alternar abas não enviou o
  pedido antigo cancelado. A suíte Social/CatalogEntry também passou.
- A suíte Friends passou novamente, incluindo troca de sessão durante pedido
  atrasado, permissões, paginação/retry, zoom, paletas e navegação do host.
- APK versionCode 39/versionName 0.4.3, minSdk 21, não depurável e certificado
  idêntico ao APK 0.4.2. Nenhum APK novo da Central.

A API e suas migrações não mudam;
não há ZIP novo nem atualização do servidor nesta versão. A API 0.4.2 continua
compatível. Downloads seguem os anexos do GitHub Release, com marcador confirmado.
