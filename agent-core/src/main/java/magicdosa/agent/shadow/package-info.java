/**
 * Shadow game: the embedded Forge game that follows the real one (rebuilt at every turn start, then the
 * turn's events re-applied) and the copier that search works on. {@link magicdosa.agent.shadow.GameCopier}
 * is Forge's own copier moved here, plus the option to copy only the top of each library.
 */
package magicdosa.agent.shadow;
