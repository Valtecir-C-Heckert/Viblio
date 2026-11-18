# Viblio 0.1.6 — Capas na Pesquisa e ícone da Conta

Corrige o fator de redução da imagem na Pesquisa: `BitmapFactory.Options.inSampleSize` começava em zero e a divisão para calcular a miniatura lançava uma exceção, capturada pelo carregador, deixando apenas o placeholder. A amostragem agora começa em 1 e cresce conforme as dimensões da imagem. Mantém a busca, os limites de imagem, o cache, o carregamento em segundo plano e a proteção de resultados antigos.

O logo principal da aba Conta passa a usar `R.drawable.ic_books_nav`, exatamente o recurso da Estante, em branco. A Conta continua sendo uma prévia, com login desativado. Não altera banco, biblioteca, capas locais, API, configurações de conexão ou APK da Central.

A verificação Android acrescenta um servidor HTTP local de teste que entrega um PNG magenta de 512×768 pixels. O teste usa a Pesquisa real e confirma que o download não envia credenciais, a decodificação resulta em 256×384, o bitmap substitui o placeholder na ImageView e mudar o filtro reutiliza o cache sem outro download. Não consulta dados pessoais ou o servidor do usuário.

## Resultados

- Build do APK e da instrumentação, lint e 92 testes unitários passaram, sem falhas/erros/ignorados.
- Instrumentação Android API 28 passou com download HTTP real do PNG, decodificação, atribuição na ImageView, conferência de tamanho/cor e reaproveitamento do cache, além da navegação e dos checks anteriores de SQLite, backup e fila. O PostgreSQL pessoal não foi acessado; a API do servidor não mudou.
- APK `br.com.viblio`, versão 0.1.6, versionCode 7, 2440495 bytes. Assinatura preservada: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`.
- SHA-256: `bc6a272b481c82b330911ac92713d9d1091b31a83e076f53ebb893d939c30b55`. Instalar por cima, sem desinstalar.
