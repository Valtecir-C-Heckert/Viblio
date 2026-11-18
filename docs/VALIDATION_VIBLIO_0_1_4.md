# Viblio 0.1.4 — catálogo PostgreSQL independente

Implementado no novo Viblio, sem usar arquitetura, autenticação ou biblioteca do Valteca antigo. Biblioteca pessoal permanece no SQLite do aparelho; o servidor fornece `public.valteca` como catálogo. Não há necessidade de migrar `biblioteca_usuario` nesta etapa. A Central não recebe novo APK nesta edição.

## Comportamento

Formulário existente recebe sugestões abaixo do título, após três caracteres e debounce de 400 ms. A lista tem altura limitada e até 20 resultados; autor/ISBN podem refinar a busca. Geração do formulário evita aplicar respostas atrasadas após editar/sair. Selecionar preenche título/autor/editora/gênero/subgêneros/saga/número/páginas disponíveis; nulos continuam vazios. Páginas vêm de `metricas_totais` apenas com `metrica_tipo` de páginas; `ordem` fracionária/fora do limite não é inventada como número de livro.

Capa externa carrega automaticamente pelo cliente de imagens sem chave de acesso. Se o usuário já escolheu uma capa, essa escolha permanece. Abrir o seletor/remover capa invalida carregamento anterior. ACTION_OPEN_DOCUMENT e os fluxos de edição, importação/exportação, progresso, avaliação e histórico foram mantidos.

Salvar mantém o ID local e salva imediatamente a biblioteca. Cadastro novo/capa manual fica em fila persistente se não há conexão. Uma resposta atualiza somente o vínculo ao catálogo; não substitui os dados pessoais. Retentativas têm UUID estável e idempotência no servidor. Fila só remove o envio correspondente: edição posterior não é apagada pela resposta de um envio antigo. Vínculos incluem identidade do catálogo para distinguir servidores diferentes. Reenvio está disponível na configuração do catálogo.

Vínculo usa as preferências do SQLite existente: não altera IDs, tabelas/sessões ou versão 9 do banco pessoal. O backup JSON versão 5 mantém as capas portáteis e acrescenta campos opcionais de vínculo; leitores anteriores ignoram esses campos. A chave fica no Android Keystore e não é exportada. Android 5 mantém uso local; catálogo com credencial requer Android 6+.

## Servidor

API FastAPI independente, executada em Docker sem root. Reaproveita a rede `viblio_default`, o host `postgres`, banco/usuário `viblio` e porta 5432. O Compose novo só acrescenta API e volume de capas; o arquivo `/opt/viblio/compose.yaml` permanece separado.

Migrações SQL aditivas criam geração dos novos IDs bigint, índices normalizados de título/autor/ISBN, extensões pg_trgm/unaccent e tabelas auxiliares para idempotência/referência de capas. Sem UPDATE/DROP do catálogo importado. Índices CONCURRENTLY; repetição da migração verificada. Se existir default de id diferente, a migração para para revisão.

Capas são limitadas por tamanho real do corpo, conteúdo validado JPEG/PNG/WebP, dimensão máxima, nomes UUID e conversão JPEG. Caminhos enviados pelo cliente não são usados. PostgreSQL armazena referências/URLs, não Base64. Escolha pessoal de um registro existente não sobrescreve a capa pública/metadados originais. Imagens e identidade do catálogo persistem no volume; nenhuma migração em massa de capas externas é executada.

## Evidência

- 88 testes unitários do Viblio passaram, zero falhas/erros/ignorados. Incluem 6 testes novos de transporte, bigint sem perda de precisão, nulos, preenchimento, multipart, isolamento de credencial nas capas e bloqueio de redirecionamento autenticado.
- Build do Viblio, APK de testes, lint e compilação Kotlin da Central passaram. Leitura de vínculos incorporada por LEFT JOIN para evitar consultas por livro. Estado do formulário conserva o vínculo/capa em recriação da Activity; reenvio não consulta o servidor quando a fila está vazia. Lint sem erros. Após a conferência final, corrigida apenas a legenda de páginas opcionais para aplicá-la exclusivamente a volumes; livros comuns continuam solicitando páginas ausentes. APK e APK de testes recompilados após esse ajuste.
- 10 testes funcionais da API passaram em PostgreSQL 17 isolado na execução final: migração repetida, busca/acentos/caixa/autor/ISBN, paginação, nulos, limite, autenticação, criação concorrente e idempotente, capas inválidas, tamanho/caminhos e preservação dos registros selecionados e distinção de páginas/número conhecidos entre edições.
- Docker construído com wheels baixadas por TLS verificado; construção online neste ambiente apresentou falha de DNS/proxy do contêiner. A opção offline usa os mesmos pins, sem desabilitar TLS ou verificações. Contêiner iniciou sem root; consulta real de saúde, busca, criação e upload/recuperação da imagem passaram. Após reiniciar, capa e identidade foram preservadas.
- Em banco isolado com 131.535 linhas sintéticas, consulta específica usou `viblio_title_search`, com tempo de execução PostgreSQL de 4,914 ms. Não é medição no Debian/CPU/dados pessoais do usuário, nem inclui rede móvel ou tempo de exibição.
- Instrumentação Android passou novamente no emulador, incluindo atualização de um banco local versão 6 sem perder ID/progresso e sem inicialização recursiva: SQLite real, vínculo bigint acima de 2^53, edição pessoal preservando sessões/página/nota, reabertura, JSON com vínculo, fila durável e conclusão condicional. Com API isolada via encaminhamento ADB, passou busca/preenchimento, envio de JPEG manual e carregamento automático/decodificação da capa devolvida. Não acessou o catálogo pessoal. Sem captura gerada ou alegação de inspeção visual completa do formulário neste teste.

## Entrega e limites

APK `br.com.viblio`, versão 0.1.4, código 5, aproximadamente 2,4 MB. Assinatura preservada: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`. Instalar por cima, sem desinstalar. SHA dos artefatos em downloads/SHA256SUMS.

ZIP da API contém fontes, migrações, Compose, configurador, testes e wheels Linux x86_64/Python 3.12. Não contém `.env`, senhas, chave, banco, catálogo ou biblioteca pessoal. Instalar em `/opt/viblio/api` e seguir viblio-api/README.md. O configurador pede a senha somente no terminal do servidor, gera uma chave nova e orienta copiá-la localmente para o APK.

Não houve implantação/acesso ao servidor Debian pessoal: a busca só fica disponível após instalar a API, executar as migrações e configurar endereço/chave no Viblio. Biblioteca/edições pessoais não são sincronizadas entre aparelhos. A configuração reutilizável de desenvolvimento foi salva como draft do ambiente; isso não publica o ambiente nem instala a API no Debian.

SHA-256 final: APK `3dd401cb004c863dd0951fc6cd0bfa8cf582a6ec004bfe07f0d51e6c5170c4f0`; ZIP `fcbecc7b1bfa70aed47f7e12732f34a992b5f5bd1353b3a1f3f2df2626174b36`.
