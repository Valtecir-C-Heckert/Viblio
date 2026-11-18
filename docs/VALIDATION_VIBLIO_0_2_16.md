# Viblio 0.2.16 — paleta mais escura

Reduz apenas a luminosidade do fundo da paleta dinâmica do Viblio: de aproximadamente 17 para 9/255. Cards (31/255), superfície ativa (42/255), matiz, saturação, textos e destaques ficam iguais a 0.2.15. O ajuste de contraste mínimo de 4,6 continua calculado sobre a mesma superfície ativa.

A capa ampliada muito discreta ao fundo volta somente em Livro atual, com o mesmo desenho mostrado pelo usuário em Piranesi. Outros detalhes de livros, coleções e demais telas usam apenas o fundo liso da paleta. Sem mudança na capa principal exibida na tela.

A mudança também se aplica às paletas locais dos amigos. Sem capa mantém cinza neutro, agora mais escuro. Central usa suas cores anteriores. Nenhuma alteração em API, contas, sincronização, banco, permissões ou biblioteca; sem ZIP ou migração nova.

## Validação

- Release, AndroidTest e compilação Kotlin da Central aprovados. Lint release: 0 erros, 47 avisos preexistentes.
- Testes nativos focados de cinco paletas e Amigos aprovados no emulador API 28, incluindo contraste, barras do sistema, restauração da paleta própria, navegação entre abas e fechamento HTTPS.
- Comparação dos pixels das capturas de 0.2.15 e 0.2.16 nas cinco cores: fundo mais escuro em todas, preenchimento dos cards exatamente igual. Textos, destaques e superfícies ativas usam os parâmetros anteriores, sem alteração.
- Livro atual volta a usar o desenhador da capa ao fundo já existente. A condição de exibição fica restrita ao livro atual; outras páginas mantêm o fundo liso.
- Sem alteração de lógica de dados; não foi necessário repetir a suíte inteira de banco/contas já validada na versão anterior.

[Cinco cores](images/viblio-0.2.16-cinco-paletas.png) · [Página de comparação](viblio-0.2.16-paletas.html).

## Instalação

Release 0.2.16, versionCode 28, `br.com.viblio`, sem depuração e com o certificado de assinatura preservado. Instalar por cima, sem desinstalar nem limpar dados. Nenhuma atualização adicional da API.

## Artefatos

- `viblio-0.2.16.apk`: SHA-256 `9025e9c29b640bf5adcc9f69788a2ec9377adc69355114a80bfecd25902f148a`.
- `viblio-0.2.16-preview.png`: SHA-256 `eca50ee038d281c60dee4ca687ab025de5aa11a91aceba808531bf79b5601c20`.

Certificado SHA-256 preservado: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`.
