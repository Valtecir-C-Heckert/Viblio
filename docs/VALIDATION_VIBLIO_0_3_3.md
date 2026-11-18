# Viblio 0.3.3 — correção visual de Metas

A 0.3.2 usava cartões grandes e separados, contrariando o esboço escolhido. Esta versão altera a apresentação de Metas: superfície contínua na paleta atual, divisórias finas, títulos pequenos, círculo e restante lado a lado, dígitos de largura limitada e links/ações compactos. Não introduz barras de progresso para os contadores de livros e páginas.

A seção inteira continua expansível e mantém o último estado local. Configurações, contagens, créditos, confirmação de compra, recibos e sincronização são os mesmos da 0.3.2. Livros, fundos de leitura e outras seções de Estatísticas não foram redesenhados. Sem alterações na API, banco, permissões, dependências ou formato dos dados.

## Verificação

- Viblio release, APK de testes, lint release e compilação Kotlin da Central.
- Instrumentação Android API 28: criação, edição, remoção, seção inteira aberta/recolhida, contadores, créditos, compra/histórico, restauração da sincronização e isolamento de perfis. Sem alterações na lógica já coberta pelos 145 testes unitários e 67 testes/20 subtestes da API de 0.3.2.
- Conferência de capturas reais do Android: seção isolada e tela de Estatísticas com Seu ritmo, Metas e Sua estante. Dados fictícios do emulador; nenhum aparelho ou servidor pessoal acessado.
- APK 0.3.3, versionCode 32, sem debuggable, com certificado preservado. Instalação por cima do aplicativo existente. Não precisa atualizar a API.

Capturas nativas com dados fictícios: [Estatísticas com Metas aberta](../downloads/viblio-0.3.3-preview.png) · [recolhida](images/viblio-0.3.3-goals-collapsed.png).
