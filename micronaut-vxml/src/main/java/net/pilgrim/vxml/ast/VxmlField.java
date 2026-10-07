package net.pilgrim.vxml.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * VoiceXML {@code <field name="..." cond="..." expr="..." type="..." slot="...">} form item.
 */
public class VxmlField implements VxmlFormItem {

    private final String name;
    private final String cond;
    private final String expr;
    private final String type;
    private final String slot;
    private final List<VxmlPrompt> prompts;
    private final List<VxmlGrammar> grammars;
    private final List<VxmlChoice> choices;
    private final VxmlFilled filled;
    private final VxmlNoInput noInput;
    private final VxmlNoMatch noMatch;

    public VxmlField(String name,
                     String cond,
                     String expr,
                     String type,
                     String slot,
                     List<VxmlPrompt> prompts,
                     List<VxmlGrammar> grammars,
                     List<VxmlChoice> choices,
                     VxmlFilled filled,
                     VxmlNoInput noInput,
                     VxmlNoMatch noMatch) {
        this.name = Objects.requireNonNull(name, "field name cannot be null").trim();
        this.cond = cond != null ? cond.trim() : null;
        this.expr = expr != null ? expr.trim() : null;
        this.type = type != null ? type.trim() : null;
        this.slot = slot != null ? slot.trim() : null;
        this.prompts = prompts != null ? new ArrayList<>(prompts) : new ArrayList<>();
        this.grammars = grammars != null ? new ArrayList<>(grammars) : new ArrayList<>();
        this.choices = choices != null ? new ArrayList<>(choices) : new ArrayList<>();
        this.filled = filled;
        this.noInput = noInput;
        this.noMatch = noMatch;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getCond() {
        return cond;
    }

    public String getExpr() {
        return expr;
    }

    public String getType() {
        return type;
    }

    public String getSlot() {
        return slot;
    }

    public List<VxmlPrompt> getPrompts() {
        return Collections.unmodifiableList(prompts);
    }

    public List<VxmlGrammar> getGrammars() {
        return Collections.unmodifiableList(grammars);
    }

    public List<VxmlChoice> getChoices() {
        return Collections.unmodifiableList(choices);
    }

    public VxmlFilled getFilled() {
        return filled;
    }

    public VxmlNoInput getNoInput() {
        return noInput;
    }

    public VxmlNoMatch getNoMatch() {
        return noMatch;
    }

    @Override
    public String toString() {
        return "VxmlField{name='" + name + "', cond='" + cond + "', type='" + type + "', prompts=" + prompts + ", grammars=" + grammars + ", choices=" + choices + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlField vxmlField)) return false;
        return Objects.equals(name, vxmlField.name)
                && Objects.equals(cond, vxmlField.cond)
                && Objects.equals(expr, vxmlField.expr)
                && Objects.equals(type, vxmlField.type)
                && Objects.equals(slot, vxmlField.slot)
                && Objects.equals(prompts, vxmlField.prompts)
                && Objects.equals(grammars, vxmlField.grammars)
                && Objects.equals(choices, vxmlField.choices)
                && Objects.equals(filled, vxmlField.filled)
                && Objects.equals(noInput, vxmlField.noInput)
                && Objects.equals(noMatch, vxmlField.noMatch);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, cond, expr, type, slot, prompts, grammars, choices, filled, noInput, noMatch);
    }
}
