/**
 * adapter/forge: the Forge to protocol translation library (design: 구조·프로토콜 모듈 구성, 2026-11-10). Entity IDs,
 * ability keys, observations, the event stream and turn-start snapshots ({@link magicdosa.adapter.forge.ForgeGameSession}),
 * candidates and payment checks, the agent seat's decision layer ({@link magicdosa.adapter.forge.AgentPlayerController}:
 * Forge's questions as requests, answers back to Forge, asked through a {@link magicdosa.adapter.forge.DecisionClient}),
 * the observed Forge AI seat, and game creation. Used by runner/forge (real games) and by agent-core (shadow game,
 * search copies). It does not depend on agent-core.
 */
package magicdosa.adapter.forge;
