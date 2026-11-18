# Viblio 0.2.15 — teste de fundo pela capa

Paleta do Viblio derivada da cor dominante da capa do livro atual. Usa matiz da capa, saturação reduzida e luminosidade baixa: fundo aproximadamente 17/255, cards 31/255, superfície ativa 42/255. Capas quentes mantêm tons marrons; azul e verde deixam de usar o marrom fixo. Sem capa/cor válida: cinzas neutros, com destaque dourado.

- Aplica a paleta a fundo, cards, campos, texto, navegação inferior e barras do sistema.
- Destaques e textos secundários ajustados para contraste mínimo de 4,6 sobre a superfície ativa; textos principais claros. Nada de brilho forte na tela.
- Cores de amigos continuam locais ao painel: card da lista e estante acompanham a capa de cada amigo; o fundo e a navegação da estante aberta usam a paleta dele. Sair restaura a paleta própria. Sem compartilhar preferências ou alterar o livro atual da conta.
- Mantém a paleta anterior enquanto a nova capa é processada fora da interface, sem reset intermediário para marrom/cinza ao trocar de livro.
- Central mantém seu fundo anterior; sem API, migração, permissão ou dependência nova. Sem textos explicativos adicionais na interface.

## Validação

- Release, AndroidTest, 129 testes unitários e compilação da Central aprovados. Lint release: zero erros.
- Suíte nativa completa aprovada no emulador API 28, incluindo Amigos, encerramento HTTPS, scanner, deep links, navegação e perfis isolados.
- Teste nativo adicional com cinco paletas aprovado: azul, verde, vermelho, roxo e marrom. Confere fundo/superfícies, barras do sistema, contraste de textos e destaques, fallback neutro e progresso pessoal preservado.
- Estante de amigo: a barra do sistema assume o azul da capa e retorna à paleta própria ao trocar para qualquer outra aba. Paleta global não é alterada pelo amigo.
- Capturas obtidas do desenho nativo. O comparativo coloca as cinco telas em uma página HTML, sem recolorir as imagens. Marrom é uma amostra de tom quente, não uma medição da capa específica de Piranesi.

[Comparativo de cinco paletas](images/viblio-0.2.15-cinco-paletas.png) · [Página de comparação independente](viblio-0.2.15-paletas.html).

## Instalação

APK release 0.2.15, versionCode 27, pacote `br.com.viblio`, depuração desativada. Mesmo certificado da versão anterior. Instalar por cima, sem desinstalar ou limpar os dados. Não atualizar a API: usa a API 0.2.13 existente. Contas, livros, páginas e sessões preservados.

## Artefato

APK: `viblio-0.2.15.apk` (4404943 bytes). SHA-256: `35fe02d82810022f62cfbf1ab6ce8f9dd89fc75c853f82b0dd9bcbe3a8873607`. Certificado SHA-256 preservado: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`.

A imagem comparativa também é publicada como anexo `viblio-0.2.15-preview.png`, verificado pelo fluxo de release antes de criar o marcador de download disponível.
