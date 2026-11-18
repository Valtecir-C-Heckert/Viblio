# Viblio 0.2.7 — classificações e ícones

APK `br.com.viblio` versão 0.2.7/code 19, release sem depuração, com o certificado de atualização existente. API 0.2.3 mantida. Sem migrações ou novo APK da Central.

## Correções

As linhas de distribuição usavam largura inteira no contador à direita, deixando os elementos com peso (nome e barra) sem espaço. O contador agora ocupa apenas a largura do número. Nome com até duas linhas e barra dividem o restante. Isso corrige a exibição de gêneros e subgêneros; o cálculo e os cadastros não foram alterados.

Foram retiradas das estatísticas as explicações “Ritmo calculado nas páginas cronometradas” e a quantidade de exemplares sem páginas informadas. O cálculo continua excluindo páginas desconhecidas, sem criar estimativas.

Todos os ícones dos cards seguem o esboço, com traços verdes e fundo circular discreto: livro aberto em Seu ritmo, livros em pé em Sua estante, círculo de progresso, barras em Gêneros e etiqueta em Subgêneros. Os dois novos símbolos usam o desenho vetorial local, sem downloads ou permissões novas.

## Verificação

- assembleRelease, assembleDebugAndroidTest e lintRelease do Viblio; compileDebugKotlin da Central.
- Regressão nativa com livros classificados: nomes Fantasia/Épica presentes e cards medidos/layoutados a 320 dp, verificando largura positiva dos nomes e barras e largura limitada dos contadores. Abrange a falha de medição que a verificação apenas por texto não detectava.
- Filtros e totais anteriores preservados: 57 páginas em Todos, 40 em Livros, 17 em Quadrinhos e zero em Mangás sem páginas conhecidas. Testa ausência dos dois textos removidos.
- Instrumentação local no emulador Android API 28, contra o APK release, incluindo a regressão de largura dos gêneros/subgêneros, navegação, histórico e persistência: passou. Não exige conexão à API pessoal. Sem nova execução dos testes Python ou de modelos, pois esta alteração é de apresentação e os dados/contratos não mudaram.
- Instalação sobre a anterior preserva a identidade do app e assinatura. Os dados de teste pertencem apenas ao emulador descartável.

Certificado SHA-256 preservado: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`. APK SHA-256: `35d67372848936638273e4d0a6402e061fb2a664ea78497e0929ec2e127e49bd`. O APK distribuído é o mesmo usado na instrumentação final.

## Entrega

GitHub autenticado → Releases → Viblio 0.2.7 → anexo APK. Instale por cima sem desinstalar. Não é necessário atualizar a API. Checksum em downloads/SHA256SUMS. Conferência visual no telefone do usuário ainda necessária.
