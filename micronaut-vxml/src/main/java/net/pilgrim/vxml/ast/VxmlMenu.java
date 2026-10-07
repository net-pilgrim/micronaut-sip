package net.pilgrim.vxml.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * VoiceXML {@code <menu id="...">} shorthand dialog.
 */
public class VxmlMenu implements VxmlNode {

    private final String id;
    private final VxmlPrompt prompt;
    private final List<VxmlChoice> choices;

    public VxmlMenu(String id, VxmlPrompt prompt, List<VxmlChoice> choices) {
        this.id = id != null ? id.trim() : null;
        this.prompt = prompt;
        this.choices = choices != null ? new ArrayList<>(choices) : new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public VxmlPrompt getPrompt() {
        return prompt;
    }

    public List<VxmlChoice> getChoices() {
        return Collections.unmodifiableList(choices);
    }

    /**
     * Desugars this menu into an equivalent {@link VxmlForm} containing a single field with choices.
     */
    public VxmlForm toForm() {
        List<VxmlPrompt> prompts = prompt != null ? List.of(prompt) : List.of();
        VxmlField field = new VxmlField(
                id != null ? "__menu_" + id : "__menu_choice",
                null,
                null,
                null,
                null,
                prompts,
                List.of(),
                choices,
                null,
                null,
                null
        );
        return new VxmlForm(id, List.of(field));
    }

    @Override
    public String toString() {
        return "VxmlMenu{id='" + id + "', prompt=" + prompt + ", choices=" + choices + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlMenu vxmlMenu)) return false;
        return Objects.equals(id, vxmlMenu.id) && Objects.equals(prompt, vxmlMenu.prompt) && Objects.equals(choices, vxmlMenu.choices);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, prompt, choices);
    }
}
