package ca.northline.ai.eval;

import java.util.List;

/** Every eval suite, run against the simulated model by each suite's test and against OpenRouter by the live eval. */
public final class EvalSuites {
    private EvalSuites() {}

    public static List<EvalSuite> all() {
        return List.of(new PlatformEval());
    }
}
