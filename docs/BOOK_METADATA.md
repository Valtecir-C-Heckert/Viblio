# Número na saga e títulos — 0.3.3

Número na saga é um campo opcional (1 a 10.000), independente do título. Pode ser cadastrado, editado, removido deixando vazio e exportado como `books[].sequenceNumber`. Os detalhes exibem “Livro N da saga”; os cards usam `#N`.

A atualização do banco SQLite de versão 1 para 2 adiciona `sequence_number` e corrige os metadados antigos sem excluir livros, capas, sessões, cronômetro, seleção ou progresso. A importação do JSON Minha Estante e dos backups antigos também faz essa separação. Backups novos preservam títulos editados pelo usuário e o número explícito, inclusive vazio.

Prefixos ordinais como `5º Amanhecer da Colheita` viram título `Amanhecer da Colheita` com número 5. Números simples no início só são separados quando o título e o autor são reconhecidos. `1984`, `7 Pilares da Saúde Alimentar` e `Sherlock Holmes 1` permanecem títulos; o sufixo de Sherlock pode indicar volume de uma edição, por isso deve ser cadastrado manualmente.

A tabela conservadora de títulos conhecidos usa título e autor, tolerando diferenças de acento e pontuação, para recuperar artigos e nomes como `A Deusa em Chamas`, `O Nome do Vento` e `Harry Potter e a Pedra Filosofal`. Não insere artigos em nomes desconhecidos. Títulos incompletos ou edições ambíguas, como `Mistborn`, continuam editáveis. Não consulta nem depende de um servidor externo.

A numeração é a fornecida pelo usuário, não uma classificação editorial: a ordem dos livros de Harry Potter e Nárnia no arquivo foi mantida. IDs existentes, páginas lidas, capas e livro atual são preservados. Nenhum JSON pessoal é incluído no repositório ou no APK.

## Validação

Os 12 testes unitários passaram; build debug e lint passaram sem erros (12 avisos). No emulador Android 9, a migração de um banco versão 1 preservou seleção, páginas, sessão salva e cronômetro pausado, separou o número e persistiu sua edição ao reabrir. O runner padrão de SQLite/cronômetro/JSON também passou.

O arquivo original foi validado no emulador em banco isolado: 159 IDs, 93 prefixos ordinais convertidos, 19.078 páginas lidas, 51 livros concluídos, 9 em leitura, duas capas e livro atual na página 354/592. Importar, fechar/reabrir, reimportar e exportar/reimportar preservaram os novos títulos e números sem duplicações. O Redmi físico não foi testado.

O teste nativo de interface editou o número para 5, salvou e confirmou “Livro 5 da saga” sem perder sessões. Cronômetro, progresso, média, estatísticas, capa desfocada e acesso às configurações do Android também passaram.
