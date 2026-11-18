# Viblio 0.1.3 — Estante atual da Central

O Viblio usa o código atual da Estante da Central, selecionado pelo módulo independente `:viblio`. Foram ativados nele os dois comportamentos já usados pela Central: capas da lista carregadas em segundo plano e barras de leitura com a cor da capa. Conserva edição, capas, notas em meia estrela, livros/mangás/quadrinhos/light novels por volumes, subgêneros, filtros, estatísticas, cronômetro, histórico, backup e pinça durante o movimento.

Pacote continua `br.com.viblio`, versão 0.1.3, código 4. Banco, imagens e preferências permanecem privados do Viblio. Não copia, substitui nem sincroniza dados da Central. Instalação sobre o Viblio anterior conserva os dados. O ícone e a Activity da Estante continuam os mesmos. Permissão única: Internet para busca de capas. Nenhuma biblioteca pessoal empacotada.

## Validação

AssembleDebug, lintDebug e os 82 testes unitários compartilhados da coleção passaram, sem falhas, erros ou ignorados. Manifesto/APK conferidos: nome Viblio, pacote independente, Activity própria e somente permissão Internet. Sem serviços Home Assistant, câmeras, Spotify ou Alfred no módulo independente.

Assinatura SHA-256 conservada: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`. SHA-256 do APK: `10101653eaebfbb45e56d95f13d96336ef7a6d7821e506f5f62aa41eb8ea9a84`. APK cerca de 2,3 MB. Verificação visual no telefone ainda necessária; o emulador apresentou falha no compositor do sistema durante a verificação separada do clima da Central.
