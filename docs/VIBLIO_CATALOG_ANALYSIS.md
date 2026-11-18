# Viblio — análise para integração com o catálogo PostgreSQL

Status: o usuário confirmou o esquema de public.valteca e esclareceu que o Valteca antigo é independente. Foi implementada uma API nova; biblioteca pessoal e autenticação antigas estão fora do escopo. As pendências históricas abaixo foram substituídas por essa definição. O restante deste documento registra a análise inicial, feita antes da implementação. A implementação e suas verificações estão em VALIDATION_VIBLIO_0_1_4.md. As migrações foram testadas apenas em banco isolado; não houve acesso ao servidor pessoal.

## O que existe neste repositório

O módulo `:viblio` usa a Estante compartilhada da Central, mas possui Activity, pacote, banco e arquivos próprios. O APK atual é 0.1.3, pacote `br.com.viblio`.

- `BooksPanel.edit` monta o formulário já existente. Título, autor, editora, gênero/subgêneros, saga, sequência, páginas e progresso estão disponíveis. Autor/editora/saga têm sugestões da biblioteca local, não de um catálogo remoto.
- `BooksPanel.saveEditor` valida os campos e salva diretamente no SQLite. Não faz requisição a um servidor.
- `LibraryRepository` mantém `books`, `collections`, `sessions`, `reading_dates`, preferências e sessão ativa. Versão SQLite 9. Atualiza livros sem usar REPLACE, preservando referências e histórico.
- `LibraryBook`, em `ReadingModels.kt`, tem identificador local UUID. Não possui referência ao catálogo, ISBN ou identificação de usuário remoto.
- `BookImages.save` já abre o arquivo escolhido no Android, limita a entrada a 12 MB, valida a imagem, redimensiona e salva um JPEG com nome UUID. A exibição, miniaturas, cores e fundos atuais usam esses arquivos privados.
- O seletor usa ACTION_OPEN_DOCUMENT com image/*; deve permanecer. O botão Buscar capa abre a busca de imagens no navegador.
- `LibraryJson` exporta/importa biblioteca, capas, sessões e datas. JSON versão 5, com imagens embutidas para tornar o backup portátil. Isso é independente do requisito de não usar Base64 no PostgreSQL: o backup existente não precisa ser removido.
- Não há Next.js, package.json, API de catálogo, integração Supabase/PostgreSQL ou autenticação de usuários neste checkout. Não há configuração de banco/VPN fornecida ao ambiente para acessar o servidor Debian.

## Alterações necessárias, mantendo a interface

Apenas acrescentar sugestões ao campo de título e indicador de busca no formulário atual. Selecionar uma sugestão preenche os campos presentes e vazios quando o catálogo não contém o dado; todos continuam editáveis. Páginas continuam necessárias para livros comuns, conforme a validação atual, e serão pedidas se ausentes no catálogo. Cadastro manual e seleção de arquivo continuam disponíveis inclusive quando a consulta falha.

Busca limitada, paginada e com debounce; cancelamento lógico de respostas antigas e proteção para não aplicar uma resposta após sair do formulário. Não baixar o catálogo inteiro. Usar identificador opaco retornado pela API, sem presumir que o id PostgreSQL seja UUID ou inteiro.

A capa externa será carregada automaticamente e poderá ser guardada no cache local usando o armazenamento atual, sem exigir download manual. A referência original será preservada. Uma capa escolhida no Android terá prioridade sobre respostas atrasadas do catálogo. Falha ao baixar uma capa não impede cadastrar o livro.

O vínculo remoto será separado do identificador local do livro. Isso preserva sessões, progresso, volumes e backups. No servidor, biblioteca pessoal referencia o catálogo; progresso, nota, status e alterações pessoais ficam vinculados ao usuário. Os metadados locais necessários para uso offline são cache/snapshot dos itens escolhidos, não uma cópia do catálogo inteiro.

O cadastro conectado terá de criar ou resolver o registro do catálogo e associá-lo ao usuário. Retentativas precisam ser idempotentes. Sem conexão, o fluxo manual local deve continuar disponível, com pendência de envio claramente indicada e preservada; confirmar este comportamento na implementação da persistência.

Metadados editados e capa manual de um livro existente devem permanecer como escolhas pessoais, sem sobrescrever automaticamente o catálogo de outros usuários.

## Arquivos envolvidos

| Arquivo atual | Alteração prevista |
| --- | --- |
| `app/src/main/java/br/com/centralcasa/ui/BooksPanel.kt` | Ponto opcional de integração, sugestões, preenchimento, indicador, prioridade da capa manual e salvamento conectado; integração desativada na Central. |
| `viblio/src/main/java/br/com/viblio/ViblioActivity.kt` | Configurar/inicializar o cliente do catálogo somente no Viblio. |
| `app/src/main/java/br/com/centralcasa/books/LibraryRepository.kt` | Migração aditiva para vínculos e envio pendente, preservando ids e referências locais. |
| `app/src/main/java/br/com/centralcasa/books/ReadingModels.kt` ou modelos separados | Dados do catálogo e vínculo pessoal, sem confundir id remoto com id local. |
| `app/src/main/java/br/com/centralcasa/books/BookImages.kt` | Reusar armazenamento/validação para capas recebidas e envio do arquivo local. |
| `app/src/main/java/br/com/centralcasa/books/LibraryJson.kt` | Preservar backups atuais e transportar vínculos opcionais sem perder imagens/progresso. |
| `viblio/build.gradle.kts` | Incluir o adaptador nas fontes do módulo; manter APK independente. |
| Novo cliente/adaptador no Viblio | API, busca limitada, autenticação, erros, capas e idempotência. |
| Backend ainda não disponível neste ambiente | Busca, criação/associação autenticada, upload e entrega das capas, migrações e testes. |

## Backend e migrações: decisões dependentes do esquema

- Confirmar nomes/tipos dos 24 campos, chave primária, índices, restrições e defaults de `public.valteca` antes de escrever SQL.
- Busca por título/autor insensível a acentos e caixa, com normalização e índices adequados (por exemplo pg_trgm e normalização com unaccent), após confirmar extensões/permissões. ISBN com busca exata normalizada.
- Não tratar títulos semelhantes como duplicatas automaticamente: edições/autores/ISBN diferentes podem ser livros distintos. Avaliar registros/índices já existentes antes de adicionar unicidade.
- Migração da `biblioteca_usuario` exige esquema original, tipos das chaves e autenticação. Preservar ids de usuário/livro e registros pessoais. Planejar leitura/exportação da origem e importação no destino; não alterar nem apagar o Supabase original.
- Não entregar uma migração SQL executável com chaves/campos assumidos. Os dados informados no pedido não contêm o DDL necessário para validar referências.
- PostgreSQL permanece acessível apenas pelo backend na rede Docker; o APK usa HTTP(S), nunca conexão SQL.
- Capas enviadas ficam em volume persistente, com limite de tamanho, validação real de formato, nomes gerados e proteção de caminhos. O banco armazena referência/URL, nunca Base64. Evitar arquivos órfãos em falhas de criação/associação.
- Usar autenticação já existente, assim que confirmada. Não criar usuário fictício nem confiar em user_id enviado pelo cliente para autorizar escrita.
- Sem cópia em massa de imagens. Separar resolução de capas externas e armazenamento local para permitir futura migração, sem executar esse processo agora.

## Informações pendentes

1. Link/acesso ao repositório do Viblio web/backend, se existente.
2. Estrutura de `public.valteca`, sem conteúdo pessoal:

```sh
sudo docker exec viblio-postgres psql -U viblio -d viblio -c '\d+ public.valteca'
```

3. DDL da `biblioteca_usuario` original e das tabelas referenciadas, além de como é feita a autenticação. Não enviar senhas ou tokens.
4. Depois de confirmar o backend, endereço que o celular usará para acessar a API, e onde o serviço será implantado. Não há motivo para solicitar senha PostgreSQL no chat: ela deve ser configurada apenas no servidor.

## Validação prevista após receber os dados

Busca parcial/acentos/caixa/autor/ISBN, limites e paginação; preenchimento com nulos; cadastro manual sem rede; prioridade da capa manual; upload com arquivos inválidos/grandes e caminhos maliciosos; isolamento entre usuários; criação idempotente sob retentativas; preservação de sessões/progresso/volumes e round-trip do backup. Testar API com PostgreSQL compatível e compilar/verificar o APK do Viblio; não afirmar integração com o servidor pessoal sem acesso e resposta funcional dele.
