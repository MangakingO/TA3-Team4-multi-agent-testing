package edu.uta.cse4392.ta3;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thrown by a node that must wait for a person: a review (human in the loop), a decision, or a model reply
 * typed in by hand (LLM_PROVIDER=manual). The graph stops at the last checkpoint; the run resumes later,
 * even from another terminal, and the node runs again from its start with the answer in the state.
 */
public final class Pause extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Map<String, Object> info;

    public Pause(Map<String, Object> info) {
        super(Json.compact(info));
        this.info = new LinkedHashMap<>(info);
    }

    /** What the run waits for: kind (llm, review_tests, review_refactoring, stuck) and the files to look at. */
    public Map<String, Object> info() {
        return info;
    }

    public String kind() {
        return String.valueOf(info.get("kind"));
    }
}
