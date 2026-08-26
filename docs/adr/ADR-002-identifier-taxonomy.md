# ADR-002 — Taxonomia de identificadores

| Campo | Valor |
|---|---|
| Status | `Accepted` |
| Data | 2026-08-25 (`America/Sao_Paulo`) |
| Escopo | Separação entre destino lógico, identidade criptográfica e localização de transporte |
| Depende de | ADR-001 |
| Detalhes ainda abertos | Namespace público de EIDs, resolução, grupos, rotação e ADR-011 |

## Contexto

No runtime atual, `destinationNodeId` exerce simultaneamente papel de destino da aplicação e hash truncado da chave pública do aparelho. Depois da descoberta BLE, um endereço transitório é resolvido para esse ID e a chave é verificada. Isso é coerente para unicast device-to-device, mas não generaliza para pessoa, serviço, grupo, mailbox, gateway ou identidade com rotação.

BPv7 já diferencia endpoint e node, porém usa “Node ID” para um endpoint singleton administrativo do Bundle Protocol Agent. Esse termo não deve ser confundido com o `MeshNodeId` criptográfico atual.

## Decisão

O OpenMesh adota tipos distintos, sem conversão implícita:

| Tipo | Significado | Estabilidade | Exemplos | Nunca significa |
|---|---|---|---|---|
| `EndpointId` | Destino lógico de uma entrega | Definida pelo namespace/resolver | aparelho, pessoa, serviço, grupo, mailbox, gateway | endereço BLE/IP ou necessariamente uma chave |
| `NodeId` | Identidade criptográfica de uma instância de protocolo | Vinculada à identity root e versão | `om1-…`; futuro `om2-…` | nome humano, autorização ou local atual |
| `TransportAddress` | Localizador opaco e adapter-scoped | Em geral efêmera | endereço BLE, group owner IP, URL de gateway, callsign | identidade global autenticada |
| `OpportunityId` | Observação local e temporária de contato | Curta; expira | adapter + epoch + nonce local | rota persistente ou identidade remota |
| `DeliveryId` | Referência local/protocolar do objeto | Conforme ADR-003/007 | creation tuple + digest protegido | endpoint ou receipt |

`EndpointId` é o parâmetro de `deliver`. A resolução de endpoint produz zero, uma ou várias possibilidades de encaminhamento, sujeitas a autorização e validade; ela não muda a intenção original.

### Mapeamento BPv7 inicial

- Todo `EndpointId` v2 on-wire é um EID BPv7 válido.
- O source do primary block usa um endpoint singleton administrativo do nó emissor (`BpNodeEndpointId`). Internamente, esse tipo continua distinto de `NodeId`, apesar da terminologia do RFC.
- O mapeamento legado singleton pode incorporar o `om1`, por exemplo `dtn://openmesh/node/{nodeId}/inbox`, para migração e fixtures.
- Esse exemplo **não** reserva namespace, não torna todos os endpoints device-bound e não decide o mecanismo público de resolução.
- `ipn` não será usado como atalho para comprimir hashes de 128/256 bits sem uma alocação ou tabela de resolução explícita. Truncamento silencioso é proibido.

### Cardinalidade e autoridade

Um endpoint pode resolver para:

- uma identidade de nó atual;
- várias identidades autorizadas, como dispositivos de uma pessoa;
- um grupo com política própria;
- um serviço replicado;
- nenhum nó observável agora.

Uma resolução assinada prova autoria da declaração, não disponibilidade, honestidade ou posse atual do endereço de transporte. O planner avalia proveniência, validade e contexto; o adapter apenas reporta fatos de link.

## Alternativas consideradas

| Alternativa | Benefício | Falha estrutural | Resultado |
|---|---|---|---|
| `EndpointId == NodeId == address` | Simplicidade do protótipo | Impede mobilidade, múltiplos devices, grupos e transportes efêmeros | `Rejected` |
| Todo endpoint é uma chave pública | Self-certification direta | Rotação muda endereço; pessoa/serviço/grupo não se reduz a uma chave de transporte | `Rejected` como regra universal |
| String sem tipos | Extensibilidade aparente | Conversões acidentais e spoofing entre namespaces | `Rejected` |
| Tipos separados + resolver explícito | Limites de autoridade claros | Exige lifecycle, cache e política de resolução | `Accepted` |

## Invariantes atendidos

- Destino da aplicação nunca é endereço de transporte.
- Identidade criptográfica permanece separada de confiança humana e autorização.
- Um adapter novo pode introduzir seu próprio endereço sem alterar API ou wire lógico.
- Mudança de conectividade não muda a identidade da entrega.
- Nenhum identificador auto-gerado é tratado como Sybil resistance.

## Consequências e ameaças

- APIs, stores e logs futuros devem carregar tipos, versões e namespace; strings nuas só cruzam limites de serialização.
- Resolvers viram fronteira de segurança: respostas têm issuer, assinatura quando aplicável, validade, proveniência e cache bounded.
- Um `TransportAddress` observado não pode substituir o `NodeId` esperado sem autenticação de sessão.
- Correlação entre EIDs estáveis e contatos pode vazar metadados. O namespace e eventual indirection/privacy layer exigem ADR própria.
- Grupos precisam definir membership, revogação, confidencialidade e semântica de receipt; “enviar a um grupo” não herda automaticamente a semântica unicast.
- Um atacante pode criar identidades ilimitadas; quotas e admission são por recursos/contexto, não apenas por `NodeId`.

## Compatibilidade

O v1 mantém `sourceNodeId` e `destinationNodeId` byte-exatos. No boundary dual-stack, esses campos são convertidos apenas para o endpoint singleton legado explicitamente versionado. Não se reinterpreta um `om1` como pessoa, serviço ou grupo. Endereços BLE nunca são persistidos como destino universal.

## Custo de reversão

**Baixo enquanto só documental; alto após expor strings sem tipo em APIs e stores.** Voltar a colapsar tipos seria simples no código, mas destruiria compatibilidade de endpoints e reintroduziria spoofing de namespace. Trocar a sintaxe concreta de EID permanece possível até o primeiro writer v2, pois a ADR fixa a separação, não um namespace público final.

## Classificação de novidade

- **Tecnologia conhecida:** EIDs de DTN, nomes lógicos, locators e identity keys separados.
- **Aplicação nova:** mapeamento explícito de `om1` legado para endpoint singleton BPv7.
- **Potencial diferenciação:** resolução offline-first com múltiplas oportunidades e proveniência explicável.
- **Hipóteses:** modelo de pessoa/grupo e privacidade do namespace.

## Fontes

- [RFC 9171, endpoint e node](https://www.rfc-editor.org/rfc/rfc9171.html)
- [RFC 9758, atualização do esquema `ipn`](https://www.rfc-editor.org/info/rfc9758/)
- [`MeshEnvelope` atual](https://github.com/lucascamilo560-wq/OpenMesh-Mobile/blob/df2dcca20d697cb77bed790d924fe940bd25aaf0/mesh-core/src/main/kotlin/com/openmesh/core/MeshEnvelope.kt)
- [`MeshNodeId` atual](https://github.com/lucascamilo560-wq/OpenMesh-Mobile/blob/df2dcca20d697cb77bed790d924fe940bd25aaf0/mesh-core/src/main/kotlin/com/openmesh/core/MeshNodeId.kt)
- [Resolução/autenticação BLE atual](https://github.com/lucascamilo560-wq/OpenMesh-Mobile/blob/df2dcca20d697cb77bed790d924fe940bd25aaf0/mesh-android/src/main/kotlin/com/openmesh/android/BlePeerIdentityClient.kt)
