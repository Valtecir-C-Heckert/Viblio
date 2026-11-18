# Viblio 0.2.3 — inventário e leitura atual

## Resultado

APK release `br.com.viblio` versão 0.2.3/code 15, sem depuração e com o mesmo certificado de atualização. Não foi gerado APK da Central, nem acessado o servidor pessoal.

Mangás e quadrinhos têm filtro de **Coleção**: completas, incompletas e total não informado, separado de **Leitura**. Disponível também em Todos, onde seleciona somente títulos de coleção. A escolha é guardada no filtro de cada tipo e no estado de navegação; a ordenação manual principal mantém o comportamento anterior de limpar filtros.

Livro atual é o primeiro atalho inferior. Abre o livro/volume atual, destaca o atalho durante essa leitura, mantém o ícone da Estante e avisa quando não existe livro atual. Confirma o abandono de formulário em edição e aguarda organização ou arquivo em processamento. O app continua abrindo na Estante para uma sessão já conectada.

O SQLite passa de 10 para 11 com uma coluna opcional `expected_volumes`. Na migração, os antigos obtidos/faltantes estabelecem o total de referência; exemplares removidos continuam ocupando posições nesse total histórico. Depois, o número faltante é derivado dos números distintos e ativos de 1 ao total, sem reduzir duas vezes por uma segunda cópia do mesmo número. Adicionar, editar, remover/restaurar ou excluir um volume atualiza a contagem por consulta, sem decrementar um contador vulnerável a repetição. Total desconhecido não vira coleção completa ao adicionar volumes.

O esquema JSON permanece 6 com o campo opcional `expectedVolumes`. A API aceita dados antigos, mantém os arquivos históricos de backup e recalcula contadores depois da mesclagem dos volumes de aparelhos diferentes. Contadores derivados não causam falsos conflitos; mudanças incompatíveis no total ainda pedem escolha. A validação recusa tipos/totais inválidos; a normalização indexa os livros por coleção para evitar percorrer todos os livros a cada título. Nenhuma migração PostgreSQL nova foi criada; 001–005 têm conteúdo idêntico ao pacote 0.2.2.

Capas, notas, histórico, datas, IDs, perfis e a regra da capa principal pelo volume 1 permanecem preservados. Não foi implementada integração com MangaDex, Google Books, Open Library, Metron ou Comic Vine; a busca manual existente não mudou.

## Verificações concluídas

- Build debug/teste e release, lint release sem erros e compilação Kotlin da Central com o código compartilhado passaram. Avisos de APIs antigas/estilo permanecem.
- **112 testes unitários** do Viblio passaram, sem falhas/erros/ignorados. Incluem aquisição versus leitura, duplicatas, lacunas, números fora do total, números desconhecidos, remoção e inferência de totais antigos.
- **48 testes Python** da API/atualizador passaram, com sete subtestes e uma advertência da dependência de testes. Novos casos verificam contadores derivados, total desconhecido, tipos inválidos, combinação de volumes adicionados offline em aparelhos diferentes e conflito real de total.
- Imagem Docker da API construída com dependências congeladas e os 20 wheels locais.
- **Instrumentação completa contra o APK release entregue**, em emulador Android API 28 e PostgreSQL/API descartáveis, passou: primeiro atalho e abertura/seleção do livro atual; migração de banco v10 com dados reais de teste; adição/edição/duplicatas/remoção/restauração de volumes; persistência e JSON; total desconhecido estável após adicionar um volume; filtros realçados pela lista de capas e restauração do estado.
- Os fluxos nativos anteriores de pesquisa HTTP, capas/cache/JPEG, notas/histórico, perfis separados, administração/convites, cadastro/login, sincronização, saída/reabertura e revogação de sessão também passaram. O teste de saída da conta aguarda o estado que permite sair e clica no mesmo evento de UI, em vez de assumir que a consulta de identidade terminou após uma espera fixa; continua verificando o diálogo e a troca real de perfil.
- O harness usa apenas emulador descartável, com limpeza de seus dados de teste; serviços e fixtures próprios foram removidos e a política temporária do teste de arraste foi restaurada.
- Certificado SHA-256 preservado: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`. Manifesto: minSdk 21, targetSdk 35, somente Internet e estado de rede.
- ZIP com CRC íntegro, código correspondente, guia, migrações históricas e 20 wheels; sem `.env`, dumps, credenciais ou dados pessoais. APK distribuído igual ao artefato release usado no teste final.

## Uso e limites

[Guia de atualização](../viblio-api/UPDATE_0_2_3.md). Atualize a API antes do APK e instale por cima sem desinstalar. Os demais aparelhos também precisam do APK novo para obter o mesmo cálculo. O script de atualização já existente pode selecionar `--version 0.2.3`; sem acesso ao checksum remoto, o valor publicado do ZIP pode ser informado com `--sha256`.

A atualização não pesquisa o total publicado de uma coleção. Se o contador antigo já estava errado, revise os faltantes no editor uma vez para ajustar o total de referência. Exemplares sem número ou fora do total não preenchem números faltantes. Não há mudança no acesso público/HTTPS, sincronização de app fechado ou transferência incremental de capas.

## Arquivos

| Arquivo | Bytes | SHA-256 |
| --- | ---: | --- |
| viblio-0.2.3.apk | 1889623 | `7af242ceec00c1c52ed3572cb0583f1de3601d23f513a1e858e1195456e47573` |
| viblio-api-0.2.3.zip | 16006705 | `fae8efce6b8f7c28112a8b4b134ce54b98f9f0097b62e3f82141244c38106759` |
