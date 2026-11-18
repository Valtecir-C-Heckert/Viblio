# Viblio

Aplicativo Android independente com apenas a Estante de livros, mangás, quadrinhos e light novels por volumes. Com sessão salva, abre diretamente na coleção e usa o ícone da Estante da Central.

Identidade de instalação: `br.com.viblio`. Última versão publicada: 0.4.1, versionCode 37. Pode coexistir com `br.com.centralcasa`; banco, capas e preferências ficam no armazenamento privado de cada aplicativo. Entrada e cadastro por convite aparecem na abertura sem sessão. A aba Conta mostra o perfil, saída e administração. A sincronização de alterações é automática com o app aberto. Sem conta, a biblioteca continua local. Arquivos JSON são ferramentas apenas de administradores; convidados não precisam importar/exportar a Estante. Nenhuma biblioteca pessoal é empacotada.

Preserva o editor, busca de capas, filtros por tipo, classificação, notas em meia estrela, volumes, estatísticas, datas de leitura, sessões e cronômetro da Estante. O pareamento opcional com Home Assistant envia somente o livro atual. Não inicializa câmeras, Spotify, CasaOS, VPN ou Nextcloud. As permissões são Internet e estado da rede, usadas pelo catálogo, capas, contas, sincronização e pareamento opcional do HA.

## Desenvolvimento

O módulo `:viblio` seleciona o código de coleção mantido em `app/src/main/java` por tarefas Sync para diretórios gerados dentro de `viblio/build`. Assim não mantém uma cópia divergente da Estante. A Activity e o manifesto próprios hospedam apenas BooksPanel, com barras de sistema, navegação de volta, seletores de documentos e restauração do estado. Deslizar a faixa de chips não aciona a troca de livro. Modelos Rgb e RestBook foram extraídos de arquivos da Central sem alterar seu comportamento.

```sh
./gradlew :viblio:assembleDebug :viblio:testDebugUnitTest :viblio:lintDebug
```

APK: `viblio/build/outputs/apk/debug/viblio-debug.apk`. Os testes da coleção são compartilhados; `:app:testDebugUnitTest` verifica a Central após alterações no código comum.

A apresentação real no Android permanece pendente quando o emulador do ambiente não disponibiliza os serviços Activity/Package.

## Entrega 0.1.0

[Baixar APK](../downloads/viblio-0.1.0.apk). Build, lint e 77 testes da coleção passaram. Os 186 testes da Central também passaram após a extração dos modelos compartilhados. Manifesto e APK verificados: nome Viblio, pacote separado, atividade própria, ícone da Estante e somente permissão Internet; as telas MainActivity da Central, CameraLivePreview e SpotifyLibraryPanel não estão no DEX. Assinatura verificada. Validação visual no telefone ainda necessária.

## Pinça nas capas — 0.1.1

[Baixar APK 0.1.1](../downloads/viblio-0.1.1.apk). Afastar os dedos amplia as capas; juntar aumenta as colunas. Funciona na Estante e nos volumes, com escolha salva por tipo/título e preservação da posição da lista. A mudança é aplicada ao terminar o gesto. [Validação compartilhada](../docs/VALIDATION_0_4_64.md).

## Pinça durante o movimento — 0.1.2

[Baixar APK 0.1.2](../downloads/viblio-0.1.2.apk). As colunas mudam enquanto os dedos se movem, por etapas. Reorganiza os cards existentes sem reconstruir a página nem decodificar as capas novamente. Títulos e números de volume voltam ao ampliar; ficam ocultos em cinco/seis colunas. Preferências e dados continuam separados da Central. [Validação compartilhada](../docs/VALIDATION_0_4_65.md).

## Estante atual da Central — 0.1.3

[Baixar APK 0.1.3](../downloads/viblio-0.1.3.apk). Atualiza a Estante com o código atual da Central, mantendo apenas a coleção. Ativa carregamento de capas em segundo plano e barras de progresso com a cor da capa, tanto em livros como nas coleções por volumes. Pinça durante o movimento, editores, gêneros/subgêneros, filtros, estatísticas, histórico, notas e backup permanecem disponíveis. Instale sobre o Viblio existente; não desinstale. Dados do Viblio ficam intactos e separados da Central. [Validação](../docs/VALIDATION_VIBLIO_0_1_3.md).

## Catálogo no servidor — 0.1.4

[APK 0.1.4](../downloads/viblio-0.1.4.apk) · [Pacote da API](../downloads/viblio-api-0.1.4.zip) · [Instalação no Debian](../viblio-api/README.md) · [Validação](../docs/VALIDATION_VIBLIO_0_1_4.md).

O formulário existente de adicionar livros pesquisa o catálogo do servidor após três letras, com debounce e sugestões limitadas. Autor/ISBN podem refinar a busca. Selecionar uma edição preenche dados conhecidos e carrega a capa pela URL. Cadastro manual, edição e arquivo de imagem local continuam disponíveis. Uma capa manual tem prioridade e pode ser enviada para o volume persistente da API; não sobrescreve a capa pública de um livro já existente.

A biblioteca pessoal e o histórico continuam locais. O livro mantém seu ID local e uma referência opcional ao catálogo, preservada no backup. Sem conexão, o cadastro manual salva no aparelho com envio pendente; é possível reenviar ao conectar o servidor. A API nova exige uma chave de instalação, guardada no Keystore no Android 6+ e fora do backup. O Android 5 mantém as funcionalidades locais, sem conexão ao catálogo. A Central não recebe novo APK nesta edição.

Configurar em Estante → ⋮ → Backup / importar → Catálogo do servidor, ou Adicionar livro → Conectar catálogo. A API precisa ser instalada no Debian antes de a busca funcionar. Nenhuma migração de contas/biblioteca do Valteca antigo é necessária.

## Pesquisa e Conta — 0.1.5

[APK 0.1.5](../downloads/viblio-0.1.5.apk) · [Validação](../docs/VALIDATION_VIBLIO_0_1_5.md).

Três abas: Estante, Pesquisa e Conta. Pesquisa busca título, autor ou ISBN no catálogo conectado, exibe capas, tipo/edição/editora e abre o cadastro atual com os dados preenchidos. Filtros por tipo atuam sobre as edições retornadas. Conta é apenas a prévia do layout, marcada Login em breve, com campos/ações de autenticação desativados. A biblioteca local funciona sem login. Não é necessário atualizar a API 0.1.4. Instale sobre o Viblio existente, sem desinstalar. A Central mantém seu APK anterior.

## Capas na Pesquisa — 0.1.6

[APK 0.1.6](../downloads/viblio-0.1.6.apk) · [Validação](../docs/VALIDATION_VIBLIO_0_1_6.md). Corrige a decodificação das capas nos resultados da Pesquisa e usa o ícone original da Estante no logo da Conta. Login continua desativado. Instale por cima, sem desinstalar. Não precisa atualizar a API nem reconfigurar a conexão.

## Ordem principal e navegação — 0.1.7

[APK 0.1.7](../downloads/viblio-0.1.7.apk) · [Validação e limites](../docs/VALIDATION_VIBLIO_0_1_7.md).

Segure uma capa ou use ⋮ → Organizar estante, arraste os títulos e toque em Salvar como principal. Salva posições por tipo, limpa filtros e mantém livros novos no fim; Cancelar preserva a ordem anterior. ⋮ → Minha ordem restaura a disposição depois de uma filtragem temporária. A ordem fica no aparelho e não entra no JSON antigo de backup. Pinça menos sensível, com mudança durante o movimento. Capas visíveis são priorizadas, com miniaturas em memória/disco e busca de título/autor em paralelo. Cadastrar vai para o topo da Pesquisa; Estatísticas vira menu separado. Instale por cima, sem desinstalar. API e dados existentes permanecem compatíveis; a Central mantém seu APK anterior.

Para validar os eventos de organização e o cache no emulador API 28, monte também `:viblio:assembleDebugAndroidTest` e execute `viblio/scripts/validate-ui.sh`. O helper restringe o dispositivo, exige biblioteca vazia para as fixtures e restaura a configuração temporária usada apenas para construir eventos sintéticos de teste.

## Capas alternativas — 0.1.8

[APK](../downloads/viblio-0.1.8.apk) · [API](../downloads/viblio-api-0.1.8.zip) · [Atualizar o servidor](../viblio-api/UPDATE_0_1_8.md) · [Validação](../docs/VALIDATION_VIBLIO_0_1_8.md).

Pesquisa → Ver capas permite escolher a principal ou uma alternativa antes de adicionar. O editor de itens vinculados oferece Capas do catálogo; a escolha só muda a biblioteca local ao salvar. Arquivos do celular são enviados como alternativas do mesmo registro, preservando a principal do catálogo. Mangás/quadrinhos por volumes usam o vínculo de cada volume; a capa do título segue o volume 1. Instale por cima para preservar a biblioteca. Atualize a API com a migração 003; mantenha `.env` e o volume. A Central não recebe APK nesta edição.

## Livro atual no Home Assistant — 0.1.9

[APK](../downloads/viblio-0.1.9.apk) · [Pacote HA](../downloads/ha-central-viblio-0.1.0.zip) · [Instalação](../home-assistant-viblio/README.md) · [Validação](../home-assistant-viblio/VALIDATION.md).

Conta → Home Assistant permite parear com a integração local e enviar título, autor, capa e páginas do livro atual. A biblioteca continua local; não lê a Central. A URL privada é guardada no Keystore. Atualiza durante o uso e ao sair; com o app fechado, o HA conserva o último estado. O catálogo PostgreSQL e as capas alternativas continuam como antes; esta integração não exige outra atualização da API.

## Abrir leitura pelo dashboard — 0.1.10

[APK](../downloads/viblio-0.1.10.apk) · [Dashboard atualizado](../downloads/ha-central-viblio-0.1.1.zip). O link `viblio://current` abre a leitura atual na própria biblioteca do aplicativo; protege um editor aberto com confirmação antes de descartar o formulário. Preserva sincronização do HA, catálogo e dados locais. Instale por cima.


## Contas e Estantes privadas — 0.2.0

[APK](../downloads/viblio-0.2.0.apk) · [API](../downloads/viblio-api-0.2.0.zip) · [Atualizar servidor](../viblio-api/UPDATE_0_2_0.md) · [Validação](../docs/VALIDATION_VIBLIO_0_2_0.md).

Primeiro administrador, cadastro por convite, login e recuperação reais. Perfis separados por usuário, incluindo banco, imagens, preferências e pareamento do HA. Vincular a biblioteca atual exige confirmação. Sincroniza capas, notas, histórico, volumes, livro atual e ordem manual; alterações no mesmo campo pedem escolha. Funciona offline. Envia alterações locais durante o uso; mudanças detectadas de outro aparelho pedem Sincronizar para combinar antes de aplicar. Não há serviço permanente com o app fechado.

Remover da Estante arquiva livros, volumes ou títulos com nota e histórico preservados. Conta → Removidos da Estante permite restaurar ou excluir definitivamente os dados pessoais, com confirmação. Catálogo e outros usuários não são alterados. Backups e recuperação disponíveis na Conta; dez versões completas no servidor, três cópias locais de segurança.

Atualize a API com a migração 004 antes de criar contas. Instale por cima sem desinstalar. A Central não recebe APK novo; seu leitor antigo pode recusar JSON esquema 6. Cadastros manuais e capas pessoais não são publicados automaticamente no catálogo após ativar contas. Sem conta, o fluxo local/legado permanece disponível.


## Entrada inicial e nome de usuário — 0.2.1

[APK](../downloads/viblio-0.2.1.apk) · [API](../downloads/viblio-api-0.2.1.zip) · [Atualizar](../viblio-api/UPDATE_0_2_1.md) · [Validação](../docs/VALIDATION_VIBLIO_0_2_1.md).

Entrada/cadastro/recuperação ficam na abertura sem sessão; conectado abre direto na Estante. A aba Conta tem perfil e saída, sem formulário de login e sem botão Home Assistant. Nomes únicos no cadastro e entrada por usuário ou e-mail. Contas antigas escolhem seu nome em Conta, preservando dados e ID. Modo offline continua disponível na abertura. Após Sair, retorna à entrada inicial.

Instale a API e migração 005 primeiro, depois APK por cima. A Central e o pareamento pessoal de HA já existente permanecem preservados. O domínio público/HTTPS ainda exige configuração separada.


## Atualização 0.2.2

Entrada redesenhada, JSON somente para administradores no Viblio e sincronização automática das alterações pendentes com o app aberto. Acervo inalterado não é reenviado; conflitos continuam explícitos. API com cotas por IP/conta, admissão limitada e respostas privadas sem cache. [Guia](../viblio-api/UPDATE_0_2_2.md) · [Segurança e limites](../viblio-api/SECURITY.md).


## Coleções completas — 0.2.3

Filtros de aquisição separados da leitura, faltantes recalculados ao adicionar/remover/restaurar volumes e atalho Livro atual no começo do menu inferior. Total desconhecido não é tratado como coleção completa. Mantém capas e dados; não inclui integração de pesquisa de mangás. [Guia da atualização](../viblio-api/UPDATE_0_2_3.md).

## Entrada e convites — 0.2.8

A versão 0.2.8/code 20 usa `https://api.viblio.com.br` por padrão. Para desenvolvimento/testes, `VIBLIO_PUBLIC_API_URL` ou `-PviblioPublicApiUrl` substitui esse endereço explicitamente; o valor vazio conserva a conexão manual. Com o endereço definitivo, a entrada não pede servidor aos novos usuários; sessões antigas mantêm o endereço já salvo até novo login. Primeira conta/configuração da instalação não é exposta no APK público. Não contém domínio fictício, chave da API ou senha do PostgreSQL.

Convites passam a mostrar código selecionável, validade local e botão Copiar código, sem endereço privado. A escolha Usar minha estante atual explicita o vínculo confirmado de uma biblioteca local; perfis já vinculados continuam separados. O mecanismo de vínculo existente foi preservado e testado no cadastro real contra a API descartável, incluindo capa, nota, páginas, sessões, datas e reentrada.

O proprietário confirmou o JSON público em dados móveis sem Wi-Fi/Tailscale e a API saudável após configurar PUBLIC_BASE_URL. [APK 0.2.8 em Releases](https://github.com/Valtecir-C-Heckert/Home-assistant/releases/tag/viblio-v0.2.8). Instale por cima; para trocar o endereço de uma sessão antiga, saia e entre na mesma conta. API 0.2.3 mantida, sem migrações novas. [Validação](../docs/VALIDATION_VIBLIO_0_2_8.md).

### Filtros com ordem manual principal

A versão 0.2.8 também corrige a restauração dos filtros: uma ordem manual salva como principal não descarta mais gênero, situação, busca e critérios de ordenação escolhidos depois. Reabrir o painel, restaurar seu estado ou passar por outro tipo da Estante carrega as preferências daquele tipo. Cancelar uma organização retorna ao filtro anterior. A ação explícita Minha ordem · limpar filtros e a organização principal guardada permanecem disponíveis.

## Capas da obra e leituras por período — 0.2.9

Galeria reúne edições equivalentes no servidor, sem fundir registros. Escolher uma imagem altera a estante pessoal, preservando páginas, notas, datas e vínculo de leitura. Pressão longa na capa dos detalhes abre Trocar capa; em coleções usa o volume 1. Livro local sem vínculo pesquisa a edição com confirmação. Capas vazias de exemplares vinculados podem ser preenchidas pela principal da própria edição em lotes de oito por abertura, com gravação condicional que não substitui capa não vazia.

Estatísticas → Por período oferece Dia/Semana/Mês/Ano, navegação por período e visão de meses. Usa sessões/datas registradas e distingue páginas conhecidas de contagem de exemplares; não infere conclusões históricas a partir do progresso atual. Inclui filtros por tipo e lista de leituras.

Requer [API 0.2.9 e migração 006](../viblio-api/UPDATE_0_2_9.md) antes de instalar o APK. API retorna páginas de 30 capas para o cliente, com editoras/ISBN quando disponíveis; imagens compartilhadas de uploads funcionam em /v1/covers/ dentro do túnel existente. [Validação](../docs/VALIDATION_VIBLIO_0_2_9.md).

## Viblio 0.2.10 — ISBN, lombadas e novas estatísticas

Leitor de código de barras ISBN na Pesquisa e no cadastro de livro; reconhecimento local, validação do ISBN e busca também pelo ISBN-10/13 equivalente. Um resultado compatível preenche os dados do cadastro, várias edições permitem escolher e nenhuma salva automaticamente. Sem resultado ou sem câmera, o cadastro manual continua disponível.

Estante → ⋮ → **Visualização da estante → Lombadas** mostra peças juntas com cor extraída da capa e título vertical; Capas permanece disponível. A escolha é salva por tipo de estante, mantendo os filtros e a ordem. Segurar abre a organização existente por capas; pinça ajusta o tamanho das lombadas. Histórico com espaçamento compacto e horário/duração alinhados; bordas internas usam a cor da capa com contraste. Estatísticas seguem a referência verde escura, com abas sublinhadas, páginas em destaque, progresso segmentado e gêneros expansíveis; o calendário no topo abre os períodos.

**Usa a API 0.2.9 existente, sem novo ZIP ou migração.** Instale por cima, sem desinstalar. GitHub autenticado → **Releases → Viblio 0.2.10 → anexo APK**. A permissão de câmera é pedida somente ao abrir o leitor; nenhuma imagem é salva ou enviada. Foco e leitura com livros físicos ainda precisam de confirmação no celular. [Validação](../docs/VALIDATION_VIBLIO_0_2_10.md).

## Viblio 0.2.11 — lombadas legíveis e seleção de períodos

Largura por faixas de 100 páginas: 1–100 = 28 dp, 101–200 = 32 dp, 201–300 = 36 dp; 2.000 páginas = 104 dp, antes do zoom. Exemplares sem total conhecido mantêm 34 dp; coleções, 52 dp. Lombadas muito largas são limitadas ao espaço da linha.

Títulos completos, com quebra de linha e fonte ajustada ao espaço, sem reticências. O texto usa preto ou branco com contraste mínimo de 4,5:1 na área uniforme da lombada; o sombreado fica nas bordas. Ícone ao lado de ⋮ alterna diretamente Capas/Lombadas, muda conforme o modo e preserva filtros e ordem. Dia/Semana/Mês/Ano agora têm abas sublinhadas, navegação em verde e título serifado, no mesmo estilo de Estatísticas.

**Sem atualização da API.** Instale por cima da versão existente, sem desinstalar. GitHub autenticado → **Releases → Viblio 0.2.11 → anexo APK**. [Validação](../docs/VALIDATION_VIBLIO_0_2_11.md).

## Viblio 0.2.12 — autor horizontal e materiais da estante

Lombadas com autor na horizontal no topo, primeiro nome/iniciais na primeira linha e restante abaixo, separador e título vertical no sentido do esboço. Fonte se adapta à largura sem cortar nomes. Textura amostrada da imagem aprovada, aplicada à cor da capa; cada fileira recebe a base de madeira da mesma imagem. Contraste considera também as variações da textura. A largura por faixas de 100 páginas e o ícone Capas/Lombadas continuam disponíveis.

A aparência é adaptativa: **não é uma reprodução idêntica pixel a pixel**. Livros, cores, larguras, densidade e fontes variam conforme os dados e a tela. Veja a captura real do app no relatório de validação.

**Sem atualização da API.** Instale por cima da versão existente, sem desinstalar. GitHub autenticado → **Releases → Viblio 0.2.12 → anexo APK**. [Validação e captura real](../docs/VALIDATION_VIBLIO_0_2_12.md).

## Viblio 0.2.13 — Amigos e visual reto

Amigos entra no lugar de Conta, com o mesmo ícone: adicione pelo nome de usuário, aceite pedidos e abra a estante de consulta de cada amigo. A capa pessoal da leitura atual aparece ao lado do nome. Conta é a última opção de ⋮ na Estante e no Livro atual. Amizade exige aceite; notas, avaliações pessoais, histórico detalhado, e-mail e configurações continuam privados. Remover amizade revoga novos acessos às estantes e capas.

Visual mais reto: controles, painéis e cartões com cantos discretos, fundo escuro neutro e destaque extraído da capa do seu livro atual, ajustado para leitura. Trocar o livro atual muda ícones ativos, linhas, números e botões; sem capa, usa dourado neutro. Preserva as estantes em Capas/Lombadas, madeira, organização, filtros e funcionamento da leitura.

**Atualize a API 0.2.13 antes do APK** — inclui a migração aditiva 007 e mantém as anteriores intactas. Baixe os dois anexos pelo GitHub autenticado → **Releases → Viblio 0.2.13**. Instale o APK por cima, sem desinstalar; seus amigos também precisam da versão nova para aceitar pedidos. [Guia do servidor](../viblio-api/UPDATE_0_2_13.md) · [Validação e capturas](../docs/VALIDATION_VIBLIO_0_2_13.md).

## Viblio 0.2.14 — correção ao sair de Amigos

- Corrige `NetworkOnMainThreadException` ao sair de Amigos após carregar uma conexão HTTPS. O encerramento TLS ocorre em segundo plano e pode ser chamado mais de uma vez com segurança.
- Cada amigo tem destaques na cor da capa de seu livro atual, na lista e na estante aberta. A sua própria paleta continua independente; sem capa disponível, usa o dourado padrão.
- Estante dos amigos em grade de capas, com progresso, zoom de 2 a 6 livros por linha e navegação das coleções. Textos explicativos retirados; ícone de recarregar redesenhado.
- Bordas de progresso das capas com cantos retos no Viblio, com espessura e espaçamento proporcionais ao zoom. Mantém as cores de andamento, livro atual e conclusão.

[Baixar APK 0.2.14](https://github.com/Valtecir-C-Heckert/Home-assistant/releases/download/viblio-v0.2.14/viblio-0.2.14.apk). Instale por cima, **sem desinstalar**. **Não precisa atualizar a API novamente**: usa a API 0.2.13 já existente, sem nova migração. [Validação](../docs/VALIDATION_VIBLIO_0_2_14.md).

## Viblio 0.2.15 — teste de fundo pela capa

O fundo, os cards e as barras acompanham o tom da capa atual, bem escurecido e com saturação reduzida. Textos e destaques têm contraste ajustado; sem capa, usa cinza neutro. Ao abrir um amigo, aplica a paleta dele e restaura a sua ao sair.

[Baixar APK 0.2.15](https://github.com/Valtecir-C-Heckert/Home-assistant/releases/download/viblio-v0.2.15/viblio-0.2.15.apk). Instale por cima, **sem desinstalar**. **Sem atualização da API.** [Validação](../docs/VALIDATION_VIBLIO_0_2_15.md).

## Viblio 0.2.16 — paleta mais escura

Somente o fundo fica mais escuro, quase preto com o tom da capa. Cards, textos e destaques mantêm as cores de 0.2.15. A capa ampliada discreta ao fundo aparece apenas em Livro atual. Vale também para o fundo das estantes dos amigos.

[Baixar APK 0.2.16](https://github.com/Valtecir-C-Heckert/Home-assistant/releases/download/viblio-v0.2.16/viblio-0.2.16.apk) · [Cinco cores](https://github.com/Valtecir-C-Heckert/Home-assistant/releases/download/viblio-v0.2.16/viblio-0.2.16-preview.png). Instale por cima, sem desinstalar. **Sem atualização da API.** [Validação](../docs/VALIDATION_VIBLIO_0_2_16.md).

## Viblio 0.2.17 — fundo do livro aberto

Ao abrir um livro, a tela de leitura volta a mostrar sua própria capa ampliada e escurecida ao fundo, mesmo quando ele não é o livro atual. As barras acompanham o tom do livro aberto. Estante, Pesquisa e Estatísticas continuam seguindo o livro atual. Cards, textos e destaques mantidos.

[Baixar APK 0.2.17](https://github.com/Valtecir-C-Heckert/Home-assistant/releases/download/viblio-v0.2.17/viblio-0.2.17.apk). Instale por cima, sem desinstalar. **Sem atualização da API.** [Validação](../docs/VALIDATION_VIBLIO_0_2_17.md).

## Viblio 0.3.1 — leitura, consulta e estante

- Card da leitura usa a cor escurecida da capa do próprio livro; capas ampliadas ao fundo preservadas.
- Barra de páginas abre registro de leitura com tempo; correções ficam disponíveis sem sessão. Status: Não iniciado, Pausado, Lendo ou Concluído.
- Consulta de livros compartilhada entre Pesquisa e Amigos, com progresso do amigo. Voltar à aba Amigos abre a lista.
- Falhas temporárias e mudanças de revisão preservam os livros visíveis; paginação tenta recuperar a revisão atual uma vez.
- Capas sem título em qualquer zoom; lombadas não quebram palavras como Sacramentadora. Rolagem ao arrastar não é mais interrompida a cada saída de uma capa.

[APK 0.3.1](https://github.com/Valtecir-C-Heckert/Home-assistant/releases/download/viblio-v0.3.1/viblio-0.3.1.apk) · [API 0.3.1](https://github.com/Valtecir-C-Heckert/Home-assistant/releases/download/viblio-v0.3.1/viblio-api-0.3.1.zip). **Atualize a API para mostrar a página do livro atual na lista de amigos**; sem migração nova. [Guia](../viblio-api/UPDATE_0_3_1.md) · [Validação](../docs/VALIDATION_VIBLIO_0_3_1.md).


## Metas pessoais — 0.3.2

Em Estatísticas, a seção Metas inteira é expansível e começa recolhida. Cada conta pode definir metas por livros, páginas ou ciclos de compra, com nome, quantidade, tipo e período. Os contadores são independentes: círculo para livros, dígitos para páginas e passos para compra. Ver livros contabilizados abre as contribuições e, nas páginas, as sessões e intervalos efetivamente contados.

Novas metas contam a partir da criação; metas de páginas/livros permitem incluir as leituras do período. Páginas usam sessões registradas, sem transformar correções de progresso em leitura. Livros com páginas conhecidas exigem sessão de conclusão. Próxima compra começa na criação, ignora livros já concluídos, consome exatamente a quantidade definida por confirmação, guarda os livros e a recompensa de cada ciclo e carrega excedentes. O mesmo livro não é reutilizado em outro ciclo dessa meta.

Configuração e ciclos ficam em preferências privadas por perfil (`SHELF_READING_GOAL:<UUID>`), incluídas no sincronismo existente sem migração de servidor. Clientes anteriores preservam essas chaves opacas; conflitos na mesma meta seguem o fluxo explícito do protocolo. Expandir/recolher é preferência local. Amigos não recebem metas nem recibos. Limites: 24 metas, 256 ciclos por meta, até 100 livros por compra e 1 MiB por configuração. Valores inválidos são preservados, identificados e podem ser removidos.


## Ajuste visual de Metas — 0.3.3

Metas segue o esboço compacto: uma superfície contínua, divisórias finas, títulos pequenos, círculo e restante lado a lado, dígitos estreitos e ações discretas. A seção inteira permanece expansível. Contagens, ciclos e sincronização mantidos. Sem atualização da API.


## Amigos e meta de compra — 0.3.4

O envio automático mantém uma tentativa pendente ao encontrar outra operação ocupada e a retoma ao concluir, sem aguardar o ciclo de um minuto. Alterações durante um envio geram nova conferência; pausar o app mantém a biblioteca local e o retorno dispara nova verificação. Formulários, sessões, conflitos e perfis continuam protegidos. Não cria um serviço de sincronização após forçar o encerramento do processo.

Amigos busca atualizações a cada 30 segundos enquanto visível, com recuo até cinco minutos após falhas. Atualiza a lista já carregada e a primeira página de uma estante; em estantes com mais de uma página carregada, preserva os livros e atualiza o progresso do livro atual a partir do resumo do amigo. A consulta é limitada a sete páginas de amigos e uma página de estante por ciclo, sem reiniciar a paginação. Não substitui a tela durante edição recente ou consulta de detalhes. Sair da aba, parar o app ou mudar de perfil invalida as respostas anteriores. Erros temporários preservam dados; revogação de acesso limpa a consulta.

Próxima compra passa a mostrar o ciclo atual, as capas/títulos dos livros que contam e a recompensa em destaque, com estados bloqueada/liberada. Créditos excedentes ficam identificados para o próximo ciclo. Registrar compra continua consumindo exatamente os livros selecionados pelo motor de metas, sem reutilização, com recibos e histórico privados. Metas inteira continua expansível; metas de livros/páginas e paleta do livro atual preservadas.

Instale nos dois celulares para receber as duas correções. Sem atualização da API.


## Tempo total por livro — 0.3.5

Dentro do livro, o card Seu ritmo destaca a soma do tempo das sessões registradas daquele livro e mantém a média por página logo abaixo. O rótulo é Tempo total de leitura durante a leitura e Tempo para concluir quando o livro está concluído. Sem sessões, mostra —; não estima tempo por páginas ou datas. Correções de página não acrescentam tempo e rascunhos ativos só entram após salvar a sessão. Usa todo o histórico registrado do livro, inclusive releituras; não representa dias corridos entre início e fim. Sem atualização da API ou migração de dados.


## Zoom local e recuperação da sincronização — 0.3.6

SHELF_SPINE_ZOOM é uma preferência local por aparelho e perfil: não entra mais no snapshot enviado, não altera o hash compartilhado e não é substituído ao baixar/restaurar uma biblioteca antiga. Demais preferências, metas, livros, sessões e progresso seguem o protocolo existente.

O app reavalia o bloqueio de sincronização legado ao abrir a sessão. Se a única divergência for a remoção do zoom antigo, usa a resolução desse campo com a revisão exata do servidor; no máximo três tentativas. Se houver conflito de páginas, dados ou metas, não escolhe automaticamente. O diálogo mostra somente os conflitos que exigem decisão e inclui a limpeza do zoom na resolução confirmada. Escolhas de dados não são reaplicadas silenciosamente a uma revisão nova. Um conflito real confirmado pausa a automação naquele perfil até a revisão manual.

A confirmação de estado sincronizado limpa o marcador antigo quando os dados foram verificados. Restauração histórica continua exigindo publicação manual; Conferir sincronização não confunde o hash local de um backup com uma publicação já concluída. API e migrações inalteradas. Basta instalar o APK por cima e abrir com conexão; não exige atualização do servidor.


## Feed, pessoas e clubes — 0.4.0

Amigos tem Feed, Meus amigos e Clubes. O diretório lista os nomes cadastrados; adicionar exige aceite antes de acompanhar estante/feed. Compartilhar minhas leituras começa desligado, com sessões novas por data, páginas e tempo. Inclui início, conclusão e avaliação.

Clubes independentes aceitam múltiplos admins. Quem cria é admin; promover, rebaixar e transferir administração verificam o papel no próprio clube e preservam pelo menos um admin. Convites precisam ser aceitos. Indicação de livros sincronizados, voto por membro, escolha entre os mais votados, prazo total, etapas por página e discussão. Datas podem ser ajustadas pelos admins com o progresso do grupo visível. Histórico de rodadas e discussão preservado.

[APK 0.4.0](https://github.com/Valtecir-C-Heckert/Home-assistant/releases/download/viblio-v0.4.0/viblio-0.4.0.apk) · [API 0.4.0](https://github.com/Valtecir-C-Heckert/Home-assistant/releases/download/viblio-v0.4.0/viblio-api-0.4.0.zip) · [Atualizar servidor primeiro](../viblio-api/UPDATE_0_4_0.md) · [Validação](../docs/VALIDATION_VIBLIO_0_4_0.md).

Na 0.4.0, Capas do catálogo apresenta miniaturas em grade de três colunas. Toque na capa para escolher; edição e paginação são preservadas.

Feed e lista de clubes acompanham o tema do seu livro atual. Dentro de um clube, a leitura escolhida pelo grupo define a paleta local, inclusive nas discussões; sair restaura o seu tema. Cards de contorno fino seguem a referência aprovada.

Histórico: toque na sessão ou no lápis do dia para editar data e duração (horas, minutos, segundos), ou apagar com confirmação. Recalcula tempo/médias e sincroniza; a página atual é mantida. Seu ritmo dentro do livro distribui total à esquerda e média à direita.

## Clubes e feed — 0.4.1

Principal do clube com livro/capa, prazo final, progresso, etapas/comentários e feed da rodada. Configurações dos admins no ⋯; histórico e participantes em páginas separadas. Leituras concluídas conservam capa, etapas e discussão no histórico. A votação permite indicar livros das estantes de todos os membros ativos, sem expor notas/dados privados.

Feed geral recebe leituras automaticamente, sem confirmação, e cada card segue a cor da capa daquela leitura. Páginas e tempo ocupam colunas separadas. Capas do catálogo abre direto em três colunas, inclusive para livro sem vínculo, sem lista de edições intermediária.

[APK 0.4.1](https://github.com/Valtecir-C-Heckert/Home-assistant/releases/download/viblio-v0.4.1/viblio-0.4.1.apk) · [API 0.4.1](https://github.com/Valtecir-C-Heckert/Home-assistant/releases/download/viblio-v0.4.1/viblio-api-0.4.1.zip) · [Atualização](../viblio-api/UPDATE_0_4_1.md) · [Validação](../docs/VALIDATION_VIBLIO_0_4_1.md). Atualize a API antes do APK.
