# Viblio 0.1.5 — Pesquisa e Conta

O Viblio passa a ter navegação inferior Estante / Pesquisa / Conta. A Estante usa os mesmos editores, filtros, volumes, sessões, notas e banco existentes. Nenhuma tela nova é adicionada à Central e nenhum APK da Central é gerado.

Pesquisa consulta a API 0.1.4 já instalada: título e autor são buscados separadamente e os resultados reunidos sem repetir um ID da mesma origem. ISBN completo usa consulta específica. Há debounce de 400 ms, proteção contra respostas antigas e cancelamento da apresentação quando a aba fica oculta. Filtros Todos / Livros / Mangás / Quadrinhos usam categoria/tipo enviados pelo servidor e se aplicam às edições retornadas, até 20 por consulta de título e 20 por autor. Não é uma contagem de todo o catálogo, nem paginação ilimitada.

Capas dos resultados são carregadas em duas tarefas de segundo plano, sem chave de acesso, com cache de 4 MB e limites de arquivo/dimensões. Abrir Adicionar à Estante preenche o editor existente e carrega a capa em segundo plano, sem salvar automaticamente. Cadastro manual permanece disponível. Ao substituir um formulário ainda em edição, o app pede confirmação para não descartar silenciosamente alterações.

A aba Conta é uma prévia visual conforme solicitado: e-mail, senha e Entrar ficam desativados, com “Login em breve”. Não envia ou armazena credenciais de conta, não cria usuários e não exige login. Continuar sem login abre a Estante. A conexão existente ao catálogo por chave de instalação permanece separada.

A navegação mantém a aba e a consulta ao recriar a Activity, conserva o formulário ao trocar de aba e desativa os gestos de livro/pinça fora da Estante. Não altera versão ou conteúdo do banco pessoal. Backup e capas existentes permanecem disponíveis; instalar sobre o Viblio, sem desinstalar.

## Validação

- 92 testes unitários do Viblio passaram, sem falhas, erros ou testes ignorados. Os novos checks exercitam identificação de ISBN, reunião de resultados sem duplicar edições, classificação/filtro por tipo e legenda de saga/volume/editora; o teste de transporte verifica categoria/tipo recebidos da API.
- Build do APK e APK de instrumentação, lint do Viblio e compilação Kotlin da Central passaram.
- Instrumentação em Android API 28 passou: troca entre Estante/Pesquisa/Conta; Conta com campos e botão de login desativados; retorno à Estante; catálogo desconectado; consulta de título seguida por autor em gateway simulado; deduplicação; legenda da edição; callback de cadastro; filtro vazio; respostas recebidas após ocultar a aba não iniciam nova consulta nem exibem resultados antigos. Os checks anteriores de SQLite real, migração de biblioteca, sessões/progresso/nota, JSON e fila de envio também passaram. Esta execução não consultou o PostgreSQL pessoal nem executou nova validação HTTP contra a API, que não mudou.
- Assinatura de instalação e pacote/versionamento conferidos. O Viblio conserva apenas a permissão Internet e a identidade `br.com.viblio`; a Central mantém seu APK anterior.
- O emulador apresentou ANR do System UI/compositor durante capturas. Os checks de views/callbacks passaram, mas não há captura visual completa aprovada das duas páginas nesta entrega. O Android API 28 do ambiente roda sem KVM; seu tempo de resposta não representa desempenho no telefone.

## Artefato

APK 0.1.5, versionCode 6, 2,446,875 bytes. Certificado SHA-256 `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`, preservado. SHA-256 do APK: `d70f91ea6b292d3ca06563a79cd7e83a03888c34b0d88e22c83d4833de21dde4`.
