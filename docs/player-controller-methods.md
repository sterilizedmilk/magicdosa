# PlayerController decision methods

Every abstract method of Forge's `PlayerController` (forge@87cb532) and how the adapter answers it for an agent seat
(2026-11-06 plan, section 2: no unrecorded decision). Generated from the sources; regenerate when the controllers
change.

- **protocol decision**: `RlPlayerController` asks agent-core (decision type given). Choices with one possible
  outcome are made without asking.
- **intended delegation (recorded)**: Forge AI answers and the decision log records it as an intended delegation.
- **flow**: not a choice itself; the human-style play path whose choices are the other methods.
- **unclassified (counted)**: Forge AI answers and each call is counted (`DecisionStats.unclassified`, printed by
  RandomGames). None of these is reached by the four target decks in the pairing runs; one that shows up is to be
  classified.

Counts: protocol decision 51, intended delegation (recorded) 3, flow 15, unclassified (counted) 41.

| Method | Params | Handling | Detail |
| --- | --- | --- | --- |
| `announceRequirements` | 4 | protocol decision | CHOOSE_OPTION (X) |
| `applyManaToCost` | 5 | protocol decision | MANA_PAYMENT |
| `arrangeForScry` | 1 | protocol decision | CHOOSE_CARDS (pile, then order) |
| `arrangeForSurveil` | 1 | protocol decision | CHOOSE_CARDS (pile, then order) |
| `assignCombatDamage` | 6 | protocol decision | DAMAGE_ASSIGNMENT |
| `chooseBinary` | 4 | protocol decision | CHOOSE_OPTION |
| `chooseCardName` | 4 | protocol decision | NAME |
| `chooseCardName` | 3 | protocol decision | NAME |
| `chooseCardsForCost` | 6 | protocol decision | CHOOSE_CARDS |
| `chooseCardsForEffect` | 7 | protocol decision | CHOOSE_CARDS |
| `chooseCardsForZoneChange` | 9 | protocol decision | CHOOSE_CARDS |
| `chooseCardsToDelve` | 2 | protocol decision | CHOOSE_CARDS |
| `chooseCardsToDiscardFrom` | 6 | protocol decision | CHOOSE_CARDS |
| `chooseCardsToDiscardToMaximumHandSize` | 1 | protocol decision | CHOOSE_CARDS |
| `chooseColor` | 3 | protocol decision | CHOOSE_OPTION |
| `chooseCounterType` | 4 | protocol decision | CHOOSE_OPTION |
| `chooseEntitiesForEffect` | 8 | protocol decision | CHOOSE_CARDS |
| `chooseManaFromPool` | 1 | protocol decision | MANA_PAYMENT |
| `chooseModeForAbility` | 5 | protocol decision | CHOOSE_OPTION (modes) |
| `chooseNumber` | 4 | protocol decision | CHOOSE_OPTION |
| `chooseNumberForCostReduction` | 3 | protocol decision | CHOOSE_OPTION |
| `chooseNumberForKeywordCost` | 5 | protocol decision | CHOOSE_OPTION |
| `chooseOptionalCosts` | 2 | protocol decision | CHOOSE_OPTION |
| `choosePermanentsToDestroy` | 5 | protocol decision | CHOOSE_CARDS |
| `choosePermanentsToSacrifice` | 5 | protocol decision | CHOOSE_CARDS |
| `chooseSaToActivateFromOpeningHand` | 1 | protocol decision | CHOOSE_OPTION |
| `chooseSingleCardForZoneChange` | 8 | protocol decision | CHOOSE_CARDS |
| `chooseSingleEntityForEffect` | 7 | protocol decision | CHOOSE_CARDS |
| `chooseSingleReplacementEffect` | 1 | protocol decision | CHOOSE_OPTION |
| `chooseSingleSpellForEffect` | 4 | protocol decision | CHOOSE_OPTION |
| `chooseSomeType` | 4 | protocol decision | NAME |
| `chooseSpellAbilitiesForEffect` | 5 | protocol decision | CHOOSE_OPTION |
| `chooseSpellAbilityToPlay()` | 0 | protocol decision | PRIORITY |
| `chooseStartingPlayer` | 1 | protocol decision | PLAY_OR_DRAW |
| `chooseTargetsFor` | 1 | protocol decision | TARGET |
| `confirmAction` | 6 | protocol decision | CHOOSE_OPTION (yes/no) |
| `confirmPayment` | 3 | protocol decision | CHOOSE_OPTION (yes/no) |
| `confirmReplacementEffect` | 4 | protocol decision | CHOOSE_OPTION (yes/no) |
| `confirmTrigger` | 1 | protocol decision | CHOOSE_OPTION (yes/no) |
| `declareAttackers` | 2 | protocol decision | DECLARE_ATTACKERS |
| `declareBlockers` | 2 | protocol decision | DECLARE_BLOCKERS |
| `getAbilityToPlay` | 3 | protocol decision | CHOOSE_OPTION |
| `mulliganKeepHand` | 2 | protocol decision | MULLIGAN |
| `orderMoveToZoneList` | 3 | protocol decision | CHOOSE_CARDS (ordered) |
| `orderSimultaneousSa` | 1 | protocol decision | TRIGGER_ORDER |
| `payCostToPreventEffect` | 4 | protocol decision | MANA_PAYMENT / CHOOSE_CARDS / CHOOSE_OPTION |
| `sideboard` | 3 | protocol decision | SIDEBOARD |
| `specifyManaCombo` | 4 | protocol decision | CHOOSE_OPTION |
| `tuckCardsViaMulligan` | 2 | protocol decision | BOTTOM_CARDS |
| `willPutCardOnTop` | 1 | protocol decision | CHOOSE_OPTION (yes/no) |
| `chooseNewTargetsFor` | 3 | intended delegation (recorded) | new targets for a copy of a spell or ability |
| `chooseSingleStaticAbility` | 1 | protocol decision when the order changes the final cost | CHOOSE_OPTION over distinct final costs (601.2f); cost-computation calls are not decisions |
| `orderBlockers` | 2 | intended delegation (recorded) | damage assignment order (old rules; not asked for the agent) |
| `orderCosts` | 1 | intended delegation (recorded) | order cost parts are paid in (engine sequencing) |
| `autoPassCancel()` | 0 | flow | GUI only |
| `awaitNextInput()` | 0 | flow | GUI only |
| `cancelAwaitNextInput()` | 0 | flow | GUI only |
| `getCostDecisionMaker` | 4 | flow | AgentCostDecision: the cards a non-mana cost uses |
| `notifyOfValue` | 3 | flow | information only |
| `orderAndPlaySimultaneousSa` | 1 | flow | puts triggers on the stack: order (orderSimultaneousSa), then each one set up like a cast |
| `payManaCost` | 6 | flow | X, cost changes, delve, then applyManaToCost |
| `playChosenSpellAbility` | 1 | flow | casts / activates the human way (PlaySpellAbility, 601.2) |
| `playSaFromPlayEffect` | 1 | flow | cast from an effect, the human way |
| `playSpellAbilityNoStack` | 2 | flow | plays a static trigger / effect ability the human way (its choices are the methods below) |
| `playTrigger` | 3 | flow | static trigger, played like playSpellAbilityNoStack |
| `reveal` | 5 | flow | information only |
| `revealAISkipCards` | 2 | flow | information only |
| `revealAnte` | 2 | flow | information only |
| `revealUnsupported` | 1 | flow | information only |
| `chooseCardsForConvokeOrImprovise` | 6 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseCardsForEffectMultiple` | 4 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseCardsForSplice` | 2 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseCardsPile` | 4 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseCardsToDiscardUnlessType` | 4 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseCardsToRevealFromHand` | 3 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseCardsYouWonToAddToDeck` | 1 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseCardToKeepStickers` | 1 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseColorAllowColorless` | 3 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseColors` | 5 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseContraptionsToCrank` | 1 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseDiceToReroll` | 1 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseFlipResult` | 3 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseKeywordForPump` | 4 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `choosePDRollToIgnore` | 1 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `choosePlayerToAssistPayment` | 4 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseProtectionType` | 2 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseRollSwapValue` | 4 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseRollToIgnore` | 1 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseRollToModify` | 1 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseRollToSwap` | 1 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseSector` | 3 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseSingleCardFace` | 4 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseSingleCardFace` | 3 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseSingleCardState` | 4 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseSprocket` | 2 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseStartingHand` | 1 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseSticker` | 4 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseStickerNamePosition` | 2 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `chooseTarget` | 2 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `confirmBidAction` | 5 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `confirmStaticApplication` | 4 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `divideShield` | 3 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `enlistAttackers` | 1 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `exertAttackers` | 1 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `helpPayForAssistSpell` | 4 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `orderAttackers` | 2 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `orderBlocker` | 3 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `payCombatCost` | 4 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `payCostDuringRoll` | 2 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |
| `vote` | 6 | unclassified (counted) | Forge AI answers; not reached by the target decks so far |

Non-mana costs paid while casting go through `AgentCostDecision` (a Forge `CostDecisionMakerBase`): sacrifice,
discard, exile, tap and return costs with a plain type and a number of cards are CHOOSE_CARDS decisions; their uncommon
forms (`+WithDifferentNames`, total-power or total-mana-value forms, "All") are recorded intended delegations
(`cost:<CostPart>(<type>)`); the other cost parts have no choice or are answered by Forge AI.
