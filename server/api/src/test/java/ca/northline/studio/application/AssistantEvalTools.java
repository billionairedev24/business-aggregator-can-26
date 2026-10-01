package ca.northline.studio.application;

import ca.northline.shared.security.MerchantPermission;
import java.util.Set;

/** Read access to {@link AssistantEval#TOOLS} for the context test in another package. */
public final class AssistantEvalTools {
    private AssistantEvalTools() {}

    public static Set<String> names() {
        return AssistantEval.TOOLS.keySet();
    }

    public static MerchantPermission permission(String name) {
        return (MerchantPermission) AssistantEval.TOOLS.get(name)[0];
    }

    public static boolean write(String name) {
        return (Boolean) AssistantEval.TOOLS.get(name)[1];
    }
}
