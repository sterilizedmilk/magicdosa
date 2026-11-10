/**
 * runner/forge: plays real Forge games for training and gates (design: 구조·프로토콜 모듈 구성, 2026-11-10). Matches and
 * sideboarding ({@link magicdosa.runner.forge.ForgeMatchRunner}), the gRPC connection to agent-core
 * ({@link magicdosa.runner.forge.GrpcDecisionClient}), game logs ({@link magicdosa.runner.forge.GameLogWriter}) and the
 * sampled candidate and copy checks ({@link magicdosa.runner.forge.CandidateCheck}). The translation itself is
 * adapter/forge.
 */
package magicdosa.runner.forge;
