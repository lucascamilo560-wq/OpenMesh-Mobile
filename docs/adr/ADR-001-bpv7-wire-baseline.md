# ADR-001 — BPv7 como baseline canônico do wire v2

| Campo | Valor |
|---|---|
| Status | `Accepted` |
| Data | 2026-08-25 (`America/Sao_Paulo`) |
| Escopo | Formato universal do objeto v2; não implementa codec de runtime |
| Decisões dependentes | ADR-002, ADR-003, ADR-006, ADR-011, ADR-013, registry de extensions |

## Contexto

O wire v1 é um codec binário posicional próprio: campos de routing, payload Base64, assinatura e estado mutável de hop vivem no mesmo `MeshEnvelope`. Ele funciona no protótipo, mas não oferece namespaces extensíveis, semântica interoperável de endpoints, administrative records ou composição padronizada de segurança. O OpenMesh poderia criar um protocolo v2 próprio ou adotar Bundle Protocol Version 7.

BPv7 já é uma tecnologia conhecida para store-carry-forward em conectividade intermitente. Ele define bundles, endpoints, lifetime, blocks e convergence layers, mas deixa cálculo de rotas e população da forwarding base fora de seu escopo. Portanto, adotá-lo não transforma o OpenMesh em “apenas uma implementação genérica de BP”: opportunity evaluation, capability provenance, energy policy, planning, runtime Android e experiência de entrega continuam sendo responsabilidades OpenMesh.

O risco principal era o overhead em mensagens pequenas sobre BLE. O [spike reproduzível](../../spikes/adr-conformance/results/BENCHMARK_REPORT.md) mediu o codec v1 real, um perfil BPv7 mínimo e um candidato BPv7+BPSec nos tamanhos exigidos.

## Decisão

**BPv7 é o formato canônico de objetos OpenMesh v2.** O OpenMesh definirá um perfil estreito, versionado e testável sobre RFC 9171 e suas atualizações, sem copiar BP para o modelo de aplicação.

A decisão inclui:

1. a API continua expressando `deliver(destination, payload, policy)`; ela não recebe nem constrói bundles;
2. o modelo interno pode ter tipos ergonômicos, mas toda informação on-wire v2 deve ter mapeamento determinístico e sem perda para o perfil BPv7;
3. Application Agent, Protocol Agent e `TransportAdapter` permanecem limites separados; o adapter cumpre papel análogo a CLA e não escolhe routing;
4. o perfil inicial usa deterministic CBOR, rejeita formas não canônicas onde a identidade do objeto dependa dos bytes e aplica limites antes de alocar memória;
5. fragmentação BP fica desabilitada inicialmente. Segmentação, resume e retransmissão pertencem ao contrato do transporte até ADR-013 resolver composição com extension/security blocks;
6. status reports BP não são promovidos automaticamente a “entrega”; ADR-006 define a taxonomia OpenMesh;
7. extension block e administrative record experimentais só podem usar faixas privadas em fixtures/laboratório. Produção exige decisão de registry e política para block desconhecido;
8. BPSec é a base preferida de composição de segurança, mas os contextos simétricos do spike não resolvem identidade assimétrica self-certifying. Essa parte permanece `Experimental` em ADR-011.

### Perfil candidato mensurado, não registry de produção

O spike usa EIDs `dtn`, um policy block privado tipo 192, Hop Count, BIB-HMAC-SHA-256 e BCB-AES-256-GCM. Esses detalhes tornam o tamanho reproduzível; **não** são uma alocação IANA nem congelam o perfil final.

## Evidência quantitativa

| Payload | Envelope v1 | BPv7 mínimo | Δ mínimo vs v1 | BPv7+BPSec | E2E v1 modelado |
|---:|---:|---:|---:|---:|---:|
| 32 B | 235 B | 241 B | +2,553% | 510 B | 602 B |
| 64 B | 279 B | 273 B | −2,151% | 542 B | 642 B |
| 256 B | 535 B | 467 B | −12,710% | 736 B | 898 B |
| 1 KiB | 1.559 B | 1.235 B | −20,783% | 1.504 B | 1.922 B |
| 10 KiB | 13.847 B | 10.451 B | −24,525% | 10.720 B | 14.210 B |

O BPv7 mínimo está em paridade no menor caso e vence a partir de 64 B porque evita Base64. O candidato BPSec é 15,282% a 24,560% menor que a construção E2E v1 modelada nos mesmos tamanhos. A segurança não é ainda semanticamente equivalente — falta a identidade assimétrica —, mas o experimento refuta a hipótese de que a estrutura BPv7, por si, inviabiliza BLE.

O gargalo dominante do caminho atual é outro: ATT MTU 23 menos ATT e o header de frame OpenMesh deixa 7 bytes de objeto por frame. Um bundle seguro de 32 B exige 73 frames hoje e 3 na sensibilidade MTU 247. Isso torna negociação, batching e desenho do CLA itens de medição, não justificativa para um segundo wire proprietário.

## Alternativas consideradas

| Alternativa | Benefícios | Custos e riscos | Resultado |
|---|---|---|---|
| Protocolo OpenMesh v2 próprio | Controle total e possibilidade de poucos bytes em casos estreitos | Reinventa semântica DTN, registry, segurança e interop; alto risco de divergência | `Rejected` por esta ADR |
| Modelo interno apenas “mapeável” para BPv7 | API limpa e liberdade local | Dois modelos wire podem divergir; conformance vira tradução permanente | Aceito somente como detalhe interno, nunca como wire concorrente |
| BPv7 completo sem perfil | Compatibilidade conceitual ampla | Opções demais, comportamento desconhecido e superfície de ataque | `Rejected`; será um perfil estreito |
| BPv7 como toda a arquitetura | Reuso máximo de termos | BP não decide oportunidade, energia, confiança nem runtime | `Rejected`; BP é objeto/protocolo, não cérebro OpenMesh |
| BPv7 perfilado | Semântica conhecida, extensibilidade e caminho de interop | CBOR/BPSec e registry trazem complexidade real | `Accepted` |

## Invariantes atendidos

- A aplicação não seleciona transporte e não conhece wire.
- Ausência de rota instantânea continua sendo espera, conforme o modelo DTN.
- Um novo adapter não exige novo formato de aplicação.
- Relays podem encaminhar ciphertext sem plaintext.
- Link procedure e entrega final permanecem semânticas distintas.
- O wire v1 não é reinterpretado silenciosamente.

## Consequências e compromissos

Positivos:

- ganha-se uma base especificada para objetos delay-tolerant e interoperabilidade futura;
- extensions podem evoluir sem alterar o primary block ou a API;
- Internet, BLE e links futuros podem transportar o mesmo objeto opaco;
- o ganho de bytes sobre Base64 cresce com payloads maiores.

Negativos:

- parser CBOR/BP/BPSec passa a ser uma fronteira de segurança crítica;
- EIDs, registry e handling de blocks desconhecidos precisam de disciplina;
- mensagens seguras minúsculas continuam caras no MTU atual;
- compatibilidade dual-stack aumenta temporariamente a matriz de testes;
- BP não oferece a política OpenMesh de aceitação durável pronta.

## Segurança e abuso

- O decoder deve impor limites de tamanho, profundidade, quantidade de blocks, targets BPSec e operações criptográficas antes de materializar payloads.
- CRC é detecção de erro, não autenticação.
- BIB/BCB não tornam capability claims verdadeiros nem impedem Sybil.
- Status reports ficam desligados por padrão para evitar amplification.
- IVs determinísticos e chaves do spike são exclusivamente fixtures públicas.
- Bundles com segurança parcial, downgrade ou critical blocks desconhecidos devem falhar de acordo com policy explícita, nunca por fallback silencioso.

## Compatibilidade

- Peers v1 continuam trocando bytes v1 exatos.
- Um node dual-stack anuncia versões suportadas sem confiar apenas na declaração remota.
- O maior nível previamente autenticado para um peer/endpoint é fixado para impedir downgrade.
- Gateways podem encapsular bytes v1 como ADU opaco v2. Tradução campo a campo só é permitida quando um test vector provar round-trip sem perda e preservar assinatura.
- Objetos v2 não são enviados a um peer comprovadamente v1-only.

## Gates de conformidade antes de runtime v2

1. parser e encoder com positive/negative vectors, limites e fuzzing;
2. round-trip byte-stable do perfil OpenMesh;
3. interoperabilidade com ao menos uma implementação BPv7 externa;
4. política documentada para cada extension block e administrative record;
5. ADR-011 promovida ou um perfil temporário explicitamente não produtivo;
6. teste em aparelhos reais de airtime, perda, resume, MTU e energia;
7. revisão dos errata aplicáveis antes de cada release do perfil.

## Custo de reversão

**Médio agora; muito alto após emitir objetos v2 persistentes.** Antes do primeiro writer de runtime, a decisão ainda pode ser revertida removendo tooling/documentação. Depois, IDs de objeto, stores, gateways e fixtures interoperáveis exigiriam migração e suporte dual. Por isso o writer v2 é bloqueado pelos gates acima.

## Classificação de novidade

- **Tecnologia conhecida:** DTN, BPv7, bundles, CLA, BPSec e status reports.
- **Aplicação nova de tecnologia conhecida:** perfil pequeno e endurecido para Android/BLE com identidade OpenMesh.
- **Potencial diferenciação:** engine explicável, energy-aware e multi-oportunidade operando sobre objetos BPv7.
- **Hipótese a validar:** interop e custo energético real em BLE de aparelhos Android.
- **Não reivindicado como novidade:** “enviar depois por qualquer rede disponível”.

## Fontes

- [RFC 9171 — Bundle Protocol Version 7](https://www.rfc-editor.org/rfc/rfc9171.html)
- [Errata do RFC 9171](https://www.rfc-editor.org/errata/rfc9171)
- [RFC 9172 — Bundle Protocol Security](https://www.rfc-editor.org/rfc/rfc9172.html)
- [RFC 9173 — Default Security Contexts](https://www.rfc-editor.org/rfc/rfc9173.html)
- [RFC 9713 — Administrative Record Types Registry](https://www.rfc-editor.org/info/rfc9713/)
- [RFC 9758 — Updates to the ipn URI Scheme](https://www.rfc-editor.org/info/rfc9758/)
- [Codec v1 auditado](https://github.com/lucascamilo560-wq/OpenMesh-Mobile/blob/df2dcca20d697cb77bed790d924fe940bd25aaf0/mesh-core/src/main/kotlin/com/openmesh/core/MeshEnvelope.kt)
- [E2E v1 auditado](https://github.com/lucascamilo560-wq/OpenMesh-Mobile/blob/df2dcca20d697cb77bed790d924fe940bd25aaf0/mesh-core/src/main/kotlin/com/openmesh/core/SecureMeshMessage.kt)
