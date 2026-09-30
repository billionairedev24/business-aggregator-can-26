package ca.northline.ai.application;

import ca.northline.ai.api.Prompt;
import ca.northline.ai.api.Prompts;

/** The real prompt library for tests and evals outside this package. */
public final class PromptLibraryAccess implements Prompts {

    private static final PromptLibrary LIBRARY = new PromptLibrary();

    @Override
    public Prompt get(String name) {
        return LIBRARY.get(name);
    }
}
