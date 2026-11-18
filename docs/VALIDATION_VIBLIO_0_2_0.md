# Viblio 0.2.0 — contas por convite

## Entrega

APK independente `br.com.viblio`, versionCode 12, nome Viblio, ícone da Estante, minSdk 21 e targetSdk 35. Contas usam Keystore e precisam do Android 6+. Instala por cima, mantendo a assinatura anterior. A Central não recebe APK novo. Servidor Debian pessoal não foi acessado; a atualização precisa ser feita conforme o guia.

Login real, primeiro administrador, convites de uso único/revogáveis, recuperação de acesso e perfis locais separados. Biblioteca atual só é vinculada após confirmação. Catálogo compartilhado consultável; livros e capas pessoais não são publicados automaticamente após ativar contas. Uso local/offline preservado.

Remoção pessoal arquiva livro/volume/título sem apagar nota/histórico. Conta → Removidos da Estante permite restaurar ou excluir definitivamente, com confirmação. A exclusão pessoal não afeta catálogo ou outras contas. Versões antigas podem continuar nos backups até a retenção vencer.

Sincronização combina campos independentes e solicita escolha em conflitos, vinculada à revisão atual. Envios automáticos não sobrepõem dados remotos: se detectam revisão nova, pedem Sincronizar para combinar. Funciona durante o uso; sem serviço permanente com o APK fechado. Uma sessão de leitura deve ser pausada com página salva antes da sincronização. Ordem manual entra na conta; itens arquivados conservam seus lugares na organização salva.

## Evidência

- Build de produto e instrumentação, lint e compilação Kotlin da Central passaram.
- 104 testes unitários do Viblio: zero falhas, erros ou ignorados.
- 22 testes da API em PostgreSQL 17 descartável: migrações repetidas, catálogo anterior, papéis, convites concorrentes/revogados, recuperação e sessões revogadas, isolamento entre contas, idempotência, mesclagem, conflito explícito, arquivamento, retenção, bases compactas, limites/validação e limitação de tentativas. Uma advertência de depreciação da dependência de testes.
- Imagem Docker da API construída com wheels congeladas, sem consulta ao PyPI durante o build. Dependências não mudaram.
- Assinatura e manifesto conferidos: somente permissão Internet; mesma chave da versão anterior.

Instrumentação final em Android API 28 passou: formulário real de cadastro por convite, confirmação da Estante própria, upload gzip do livro, logout com perfil convidado vazio e novo login reabrindo a biblioteca com nota/progresso preservados. Também passaram navegação, pinça/organização anteriores, migração SQLite, importação/exportação, arquivamento/restauração, separação de banco/preferências/imagens, execução enfileirada no perfil correto e preservação byte a byte do JPEG restaurado.

O primeiro teste de confirmação não encontrou o botão por causa da transformação do texto para maiúsculas no Android. O harness passou a comparar o rótulo sem distinguir caixa; as asserções de resultado permaneceram, e o fluxo completo passou após a correção. Fixtures removidas e política temporária do harness restaurada. A apresentação no celular real ainda precisa ser conferida.

## Armazenamento e limites

SQLite versão 10, migração aditiva de arquivamento. JSON esquema 6, importando versões 1 a 6. A Central antiga pode recusar JSON 6. Banco/capas/preferências/HA separados por perfil; trabalho enfileirado captura o perfil de origem. Logout esconde o perfil sem apagar sua cópia local.

Sessões opacas de 30 dias; senhas scrypt com salt e tokens somente em hash no PostgreSQL. Administrador acessa convites/recuperação, sem endpoint para ler o acervo de outro usuário. Sem SMTP ou validação de propriedade do e-mail nesta beta. A chave de instalação não deve ser entregue aos convidados. Sem migração de autenticação Valteca antiga ou KOReader.

Snapshot completo até 32 MiB descompactado incluindo JPEGs; 24 MiB comprimido. Até 5.000 livros/volumes, 5.000 títulos, 20.000 sessões e 100.000 datas. Erros preservam os dados locais. Dez backups completos por conta, mil bases compactas; bases antigas demais exigem revisão como nova importação, inclusive de exclusões definitivas. Três backups locais de segurança antes de substituir dados. PostgreSQL guarda referências e hashes das capas; Base64 fica no arquivo privado de backup, não no banco. Capas JPEG restauradas sem nova compressão.

Backup de desastre reúne dump, volume e configuração protegida. O guia contém parada somente da API durante a cópia. A execução no servidor real e a apresentação no telefone permanecem para conferência após a instalação.

## Artefatos

viblio-0.2.0.apk: 2720495 bytes. SHA-256 `bc3391c3106e31e2bd25ce429329df3e1f40d65ae52dc6ae8ca2a9b8e2f595ce`.

viblio-api-0.2.0.zip: 15976289 bytes. SHA-256 `18e8717729b13010a5e23335159df6b13e20f8c803d1ab50104aade21c4d42cb`.

Assinatura SHA-256: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`. ZIP CRC válido, migração 004/guia/wheels presentes, nenhum `.env` ou banco pessoal.
