package ca.northline.ai.eval;

import ca.northline.ai.AiTestKit;

/**
 * One AI feature's evaluation over its labelled set. {@link #run} drives the feature's own code through {@code kit}
 * (the simulated model behind the mock OpenRouter in CI, the real OpenRouter in {@link AiEvalLiveTest}) and grades every
 * case. Register each suite in {@link EvalSuites}.
 */
public interface EvalSuite {

    String name();

    /** The live pass rate a release needs ({@code AI_EVAL_MIN_PASS} overrides). */
    default double gate() {
        return 0.8;
    }

    EvalReport run(AiTestKit kit, String mode);

    /** The set the simulated model replays. */
    LabelledSet set();
}
