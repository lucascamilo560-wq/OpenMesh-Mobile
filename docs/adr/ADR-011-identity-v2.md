# ADR-011 — Identity v2, separação de chaves e integração BPSec

| Campo | Valor |
|---|---|
| Status | `Experimental` |
| Data | 2026-08-25 (`America/Sao_Paulo`) |
| Escopo | Arquitetura criptográfica futura; proibida para produção enquanto os gates estiverem abertos |
| Depende de | ADR-001, ADR-002, ADR-003 e ADR-006 |
| Decisão posterior | ADR-012 para rotação/recovery/trust; perfil de segurança/registry |

## Contexto

O v1 usa a mesma chave P-256 de longo prazo para ECDSA, ECDH, derivação de `NodeId` e challenge-response. O segredo ECDH estático é passado por SHA-256 com um prefixo para virar chave AES-GCM. Isso preserva confidencialidade e autenticação no protótipo, mas mistura finalidades, não usa um KDF padronizado com contexto completo, não oferece rotação/delegação e compromete mensagens históricas se a chave estática receptora for exposta.

O `NodeId` atual usa 128 bits do SHA-256 da chave pública X.509. Isso é suficiente contra colisão acidental no protótipo, porém faz rotação significar nova identidade. Alongar o ID não cria Sybil resistance: um atacante continua podendo gerar muitas chaves.

O spike BPv7 usa BIB-HMAC-SHA-256 e BCB-AES-256-GCM, os contextos padrão do RFC 9173. Eles medem uma composição BPSec válida, mas pressupõem segredo simétrico e não substituem prova de origem assimétrica self-certifying nem distribuição da content-encryption key.

## Decisão experimental

As seguintes propriedades já são obrigatórias para qualquer proposta v2:

1. `IdentityRoot`, `SigningKey`, `RecipientEncryptionKey` e chaves efêmeras de sessão são papéis distintos;
2. cada key/credential declara versão, algorithm suite, purpose, validity e issuer/delegation;
3. derivação de chaves usa KDF padronizado com salt/context/domain separation; concatenação + hash não é aceita em construção nova;
4. payload usa AEAD e nonces únicos segundo o requisito da suite;
5. origem, recipient key, object identity, routing metadata protegido, suite e downgrade context ficam criptograficamente vinculados;
6. relays não recebem chave de conteúdo nem plaintext;
7. `NodeId` v2 é versionado e usa pelo menos 256 bits de digest de uma identity root/canonical descriptor, sem alegar Sybil resistance;
8. endpoints autorizam identities/keys; não são derivados obrigatoriamente de uma chave de aparelho;
9. rotação e revogação não podem exigir reendereçar silenciosamente todo endpoint;
10. nenhuma suite v2 entra no runtime antes dos gates desta ADR.

Os bytes, algoritmos e integração BPSec **não estão aceitos ainda**. Esse é o motivo do status `Experimental`.

## Modelo de papéis

| Papel | Função | Exposição esperada | Rotação |
|---|---|---|---|
| `IdentityRoot` | Autorizar chaves operacionais e derivar identidade versionada | Uso raro; preferir hardware/non-exportable quando disponível | Rara, com procedimento de continuidade/recovery |
| `SigningKey` | Assinar objetos, receipts, claims e handshakes por domínio | Operacional; escopos separados podem exigir subchaves | Regular, delegada pela root |
| `RecipientEncryptionKey` | Receber encapsulamento de content keys/mensagens assíncronas | Publicável via resolução autenticada | Regular, com overlap de decrypt |
| `SessionEphemeralKey` | Autenticar/derivar segredo de sessão quando há contato bidirecional | Efêmera | Por sessão/epoch |
| `ContentEncryptionKey` | AEAD do payload/ADU | Encapsulada só a recipients autorizados | Por objeto ou grupo policy |

Uma única chave física pode ser inevitável em hardware legado, mas as credenciais e domínios lógicos continuam separados; reutilização de material requer justificativa e teste específicos, não fallback automático.

## Suites candidatas a medir, não escolhidas

| Candidata | Vantagens | Questões bloqueadoras |
|---|---|---|
| P-256 distinto para ECDSA e ECDH + HKDF-SHA-256 + AES-GCM | Migração próxima ao v1 e chance maior de hardware-backed Android | DER/canonical signature, HPKE profile, forward secrecy e suporte real por API/minSdk |
| Ed25519 + X25519/HPKE + AEAD padronizada | Separação natural, formatos pequenos e ecossistema moderno | Android Keystore/provider matrix, backup, hardware e interop BP/BPSec |
| COSE/HPKE dentro da ADU + BPSec para policy/routing | Primitivas padronizadas e relay opacity | Duas camadas, tamanho, registry e regras de assinatura duplicada |
| Security contexts BPSec assimétricos OpenMesh | Integração direta ao bundle | Especificação/registro próprios, revisão externa e poucas implementações interoperáveis |

Não haverá escolha por preferência estética. O spike seguinte mede disponibilidade Android API 26–36, hardware-backed behavior, bytes, tempo, energia, known-answer vectors, recovery e interoperabilidade.

## Integração com BPv7/BPSec

- BCB-AES-GCM pode proteger ADU usando uma content key, desde que encapsulamento/distribuição dessa chave seja definido.
- BIB-HMAC é adequado quando existe security association simétrica, mas não representa assinatura pública do originador.
- O primary block e requisitos OpenMesh que controlam routing devem ser integrity-protected conforme o security policy; Hop Count/Age continuam mutáveis sob regras próprias.
- A source EID, identity root e signing key precisam de binding verificável e versionado.
- Falha em reconhecer suite/critical security block não autoriza plaintext, v1 ou algoritmo mais fraco.
- Um perfil assimétrico pode exigir security context ou objeto COSE padronizado; nenhuma alternativa será chamada “BPSec compliant” sem interop e análise normativa.

## Forward secrecy e assincronia

DTN dificulta forward secrecy: o destinatário pode estar offline por dias, então o emissor precisa cifrar para material público pré-publicado. HPKE com ephemeral sender reduz reutilização de segredo, mas comprometimento posterior da private recipient key ainda pode expor mensagens gravadas dependendo do modo e da lifecycle da chave. Prekeys, ratchets e sessões oferecem propriedades diferentes e têm custo de disponibilidade/recovery.

Portanto, “forward secrecy” só pode aparecer como propriedade nomeada de uma suite com adversary model e teste; não será inferida por usar chave efêmera em um lado.

## Threat model e limites

- **Sybil:** self-certifying ID impede falsificação de uma identidade existente, não criação barata de muitas identidades. Admission, rate limits, resource puzzles ou trust federado são decisões separadas.
- **Replay:** object/receipt IDs, creation tuple, validity, session transcript e tombstones precisam estar ligados e deduplicados.
- **Key substitution:** resolução associa endpoint, identity root, purpose e validity; uma chave válida para signing não vale para encryption.
- **Downgrade:** highest authenticated suite/version pinning e transcript binding; primeira associação precisa de policy explícita.
- **Compromised relay:** não vê plaintext, mas pode observar metadata, atrasar, descartar, replicar ou negar serviço.
- **Metadata leakage:** BPSec de payload não esconde EIDs e forwarding metadata necessários. Privacidade de tráfego exige camada/ADR própria.
- **Compromised endpoint:** nenhum protocolo prova que uma pessoa leu ou usou o conteúdo; receipt prova apenas a semântica assinada.
- **Nonce misuse:** allocator/recovery precisa sobreviver a crash ou usar construção que garanta unicidade sem contador frágil.

## Compatibilidade e migração

- `om1` e E2E v1 permanecem somente no codec/identity legado.
- `om2` é um namespace diferente; não reinterpreta digest de 128 bits como root de 256 bits.
- Uma identity v2 pode assinar uma declaração de associação ao `om1`, mas aceitação depende de trust policy; associação não torna chaves equivalentes.
- Peers dual-stack fixam a maior suite previamente autenticada e recusam downgrade silencioso.
- Store precisa reter suite/key IDs necessários para abrir objetos durante o overlap de rotação.
- Nenhuma migration destrói a única private key legível antes de backup/recovery e round-trip verificados.

## Gates para promover a `Accepted`

1. threat model revisado por pessoa externa ao autor da implementação;
2. duas suites candidatas medidas no Android API 26–36 relevante, inclusive Keystore/hardware quando disponível;
3. test vectors públicos para root descriptor, IDs, signatures, KDF, AEAD, key encapsulation, receipts e downgrade;
4. estratégia de nonce e crash recovery demonstrada;
5. rotação, revogação, backup e perda de aparelho exercitadas;
6. interoperabilidade BPSec/COSE/HPKE conforme a alternativa escolhida;
7. fuzzing e negative tests de parsers/algoritmos/suite negotiation;
8. revisão explícita de metadata leakage e group messaging;
9. proibição comprovada de key reuse entre purposes;
10. plano de crypto agility que não permite downgrade.

## Alternativas consideradas

| Alternativa | Falha | Resultado |
|---|---|---|
| Manter uma P-256 para tudo | Reuso de chave, rotação acoplada e KDF ad hoc | `Rejected` para v2 |
| Escolher Ed/X25519 imediatamente | Ignora matriz Android/hardware e recovery | Não aceita; permanece candidata |
| Usar apenas BPSec default | Não entrega self-certifying asymmetric origin/key distribution | `Rejected` como solução completa |
| Criptografia OpenMesh nova | Risco de desenho não revisado | `Rejected` |
| Separar papéis e medir suites padronizadas | Mais trabalho e bytes, menor risco sistêmico | Direção `Experimental` aceita |

## Invariantes atendidos

- Relay não precisa conhecer plaintext.
- Identidade de endpoint, identidade criptográfica e confiança humana não colapsam.
- Chaves têm finalidade única e algorithms são versionados.
- Downgrade não é fallback silencioso.
- Receipt final exige autoridade do endpoint, não só autenticação de link.
- Assinatura de capability prova autoria, não veracidade.

## Custo de reversão

**Baixo agora e extremo depois de identities v2 existirem.** Trocar suite/documento canônico depois altera IDs, key directories, receipts, backups e capacidade de abrir dados antigos. O status experimental é uma contenção deliberada desse lock-in.

## Classificação de novidade

- **Tecnologia conhecida:** key separation, identity roots, HKDF, AEAD, HPKE, COSE, rotation e BPSec.
- **Aplicação nova:** ligação desses mecanismos a endpoint/node separados e receipts DTN no Android.
- **Potencial diferenciação:** identidade operacional offline com adapters heterogêneos e recovery utilizável.
- **Hipóteses:** suite com melhor suporte hardware/interop e nível de forward secrecy viável em DTN.
- **Não é novidade:** IDs self-certifying e challenge-response.

## Fontes

- [RFC 9172 — Bundle Protocol Security](https://www.rfc-editor.org/rfc/rfc9172.html)
- [RFC 9173 — Default Security Contexts](https://www.rfc-editor.org/rfc/rfc9173.html)
- [RFC 5869 — HKDF](https://www.rfc-editor.org/rfc/rfc5869.html)
- [RFC 9180 — HPKE](https://www.rfc-editor.org/rfc/rfc9180.html)
- [RFC 9052 — COSE Structures and Process](https://www.rfc-editor.org/rfc/rfc9052.html)
- [`MeshCrypto` atual](https://github.com/lucascamilo560-wq/OpenMesh-Mobile/blob/df2dcca20d697cb77bed790d924fe940bd25aaf0/mesh-core/src/main/kotlin/com/openmesh/core/MeshCrypto.kt)
- [`AndroidMeshIdentityStore` atual](https://github.com/lucascamilo560-wq/OpenMesh-Mobile/blob/df2dcca20d697cb77bed790d924fe940bd25aaf0/mesh-android/src/main/kotlin/com/openmesh/android/AndroidMeshIdentityStore.kt)
- [`SecureMeshMessage` atual](https://github.com/lucascamilo560-wq/OpenMesh-Mobile/blob/df2dcca20d697cb77bed790d924fe940bd25aaf0/mesh-core/src/main/kotlin/com/openmesh/core/SecureMeshMessage.kt)
