# Viblio 0.2.9 — capas por obra e leitura por período

APK release br.com.viblio 0.2.9/code 21, endereço fixo https://api.viblio.com.br, sem depuração. API 0.2.9/version 4 e migração 006 antes do APK. Sem novo APK da Central, sem mudança de SQLite/JSON pessoal, contas, IDs, credenciais ou volume. O servidor pessoal não foi acessado nem atualizado pelo ambiente.

## Capas

A API reúne capas das edições de uma obra, mantendo cada registro original. work_key igual é usado quando disponível; fallback exige título+autor normalizados e compatibilidade de work_key, tipo e número. Autor ausente sem work_key não agrupa, nem mistura volumes ou obras com IDs conhecidos conflitantes. Não há promessa de reconciliar toda grafia de autor/tradução sem identificador confiável.

Listagem autenticada e somente leitura, paginada (APK 30 por página, API 1–50), com cursor limitado e consultas de até 1000 edições/5000 uploads; truncated informa excesso. Deduplica URL e fingerprint conhecido de upload. Não baixa imagens externas no servidor para comparar conteúdo. Imagens pessoais de snapshots não são publicadas nessa galeria.

Busca e galeria apresentam uploads conhecidos pelo alias /v1/covers/ para funcionar no túnel existente /v1/.*. /covers/ continua no backend. URLs e metadados originais não são reescritos no PostgreSQL. O hash usado nos índices mantém tamanho limitado para textos importados longos; a consulta compara também o texto exato normalizado, sem confiar em ausência de colisões do hash.

Segurar a capa nos detalhes, ou o menu Trocar capa, abre galeria/arquivo do celular. Coleções usam o volume 1. Galeria mostra editora, ISBN e páginas quando disponíveis e carrega opções adicionais. Um exemplar sem vínculo pede escolha de edição em vez de associar silenciosamente por título. A imagem selecionada fica na estante pessoal; páginas, nota e histórico não recebem metadados de outra edição. Editar um livro existente e selecionar dados do catálogo preserva a capa já não vazia; escolha explícita de capa deixa de ser tratada como preenchimento automático substituível.

Preenchimento de capas vazias: até oito exemplares já vinculados por abertura, em worker, verificando a origem. Só usa a principal da própria edição, não escolhe aleatoriamente uma capa de outra edição. UPDATE condicional exige capa ainda vazia, exemplar ativo e mesmo vínculo; não sobrescreve uma imagem escolhida enquanto o download estava em andamento. IDs sem vínculo, capa não vazia e edição sem principal não são adivinhados. Novas imagens entram na sincronização pessoal existente.

## Estatísticas

Resumo/Por período mantém o filtro Todos/Livros/Mangás/Quadrinhos. Dia/Semana/Mês/Ano permitem navegar anterior/próximo e voltar ao período atual. Conta exemplares com sessões/datas registradas, páginas cronometradas conhecidas e duração salva. Visão anual contém 12 resumos mensais; nos outros períodos há acesso ao resumo do ano. Lista de leituras paginada em dez itens.

Semana de segunda a domingo, intervalos com fim exclusivo, mês/ano reais do calendário. Sessão usa readingDay editado ou data local de startedAt. Datas manuais contam presença de leitura, sem inventar páginas/tempo. Não infere quando uma obra foi concluída a partir do estado atual. Como o resumo anterior, considera exemplares ativos da Estante e o tipo selecionado; um volume é um exemplar, não uma coleção inteira. Sem registros históricos, não há atribuição estimada a meses. Estado desse painel é de navegação local; não cria campos de sincronização novos.

## Evidências

- Build assembleRelease, assembleDebugAndroidTest, lintRelease, 116 testes unitários e compilação Kotlin da Central: passaram. Testes novos cobrem semanas entre anos, fevereiro bissexto, navegação mensal, dia editado, datas manuais, páginas desconhecidas e metadados/paginação HTTP da galeria.
- 58 testes Python passaram, com 20 subtestes: autenticação, galerias de seis edições equivalentes, outros autores/tipos/volumes excluídos, work_keys conflitantes, autor desconhecido, URLs repetidas, paginação, imagens via /v1, URLs legadas sem reescrever banco, metadados importados longos aceitos pelos índices, contas/perfis, sincronização e atualizador. Inclui cinco testes do helper de implantação, mantidos no repositório.
- Imagem Docker da API construída com os 20 wheels congelados, sem novas dependências.
- Instrumentação local completa contra o APK release entregue, Android API 28: passou. Confirma galeria paginada e deduplicada na UI, seleção explícita com metadados, preenchimento condicional que preserva imagem/progresso/nota e recusa outra origem. Confere valores por Dia/Semana/Mês/Ano e regressões anteriores de filtros, histórico, estatísticas, inventário, leitura, navegação, perfis e persistência.
- A instrumentação final não realizou login/requisições no servidor pessoal. Os testes da API usam somente PostgreSQL descartável viblio_test*. A correspondência com as seis capas reais e a aparência no telefone serão conferidas após atualizar o servidor.
- Assinatura SHA-256 preservada: 5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0. minSdk 21, targetSdk 35, permissões Internet/estado de rede. Sem depuração e sem dados pessoais no APK/ZIP.
- APK distribuído idêntico ao da instrumentação final: SHA-256 5327f121f30a2dd1cc4f6c760723defc5c3cb3ad0dafc405291d9d7b91e8078c.
- ZIP com CRC válido e estrutura aceita pelo atualizador independente, 20 wheels, migrações 001–005 byte a byte idênticas ao pacote 0.2.3 e migração 006 aditiva/repetível. Sem .env, dumps, links ou caches. SHA-256 final em downloads/SHA256SUMS.

## Implantar

[Guia completo](../viblio-api/UPDATE_0_2_9.md): localizar o ZIP novo no Nextcloud, copiar para /tmp, verificar checksum, fazer backup de banco/capas/snapshots/.env, extrair como `<usuario>` sem substituir .env, confirmar 006 existe e executá-la fora de transação, recriar somente o projeto viblio-catalog-api e esperar healthy. Não down -v, não configure.py, não alterar /opt/viblio/compose.yaml. API antes do APK; instalar por cima sem desinstalar. URL pública e túnel existentes permanecem os mesmos.
