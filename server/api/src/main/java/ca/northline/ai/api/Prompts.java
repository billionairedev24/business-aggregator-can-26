package ca.northline.ai.api;

/** The prompt library: the highest version of each file under {@code classpath:ai/prompts/}. */
public interface Prompts {

    /** Throws {@link IllegalArgumentException} for an unknown name. */
    Prompt get(String name);
}
