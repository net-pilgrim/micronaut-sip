package net.pilgrim.vxml.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Root VoiceXML document node ({@code <vxml version="2.1">}).
 */
public class VxmlDocument implements VxmlNode {

    private final String version;
    private final String xmlns;
    private final List<VxmlVar> vars;
    private final List<VxmlForm> forms;

    public VxmlDocument(String version, String xmlns, List<VxmlVar> vars, List<VxmlForm> forms) {
        this.version = version != null ? version.trim() : "2.1";
        this.xmlns = xmlns != null ? xmlns.trim() : null;
        this.vars = vars != null ? new ArrayList<>(vars) : new ArrayList<>();
        this.forms = forms != null ? new ArrayList<>(forms) : new ArrayList<>();
    }

    public String getVersion() {
        return version;
    }

    public String getXmlns() {
        return xmlns;
    }

    public List<VxmlVar> getVars() {
        return Collections.unmodifiableList(vars);
    }

    public List<VxmlForm> getForms() {
        return Collections.unmodifiableList(forms);
    }

    /**
     * Resolves a form by ID. If id starts with '#', the prefix is stripped.
     * If id is null, empty, or "#", returns the first form if present.
     */
    public Optional<VxmlForm> findForm(String id) {
        if (id == null || id.isBlank() || id.trim().equals("#")) {
            return forms.isEmpty() ? Optional.empty() : Optional.of(forms.get(0));
        }
        String cleanId = id.trim().startsWith("#") ? id.trim().substring(1) : id.trim();
        for (VxmlForm form : forms) {
            if (cleanId.equals(form.getId())) {
                return Optional.of(form);
            }
        }
        return Optional.empty();
    }

    public Optional<VxmlForm> getFirstForm() {
        return forms.isEmpty() ? Optional.empty() : Optional.of(forms.get(0));
    }

    @Override
    public String toString() {
        return "VxmlDocument{version='" + version + "', vars=" + vars + ", forms=" + forms + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlDocument that)) return false;
        return Objects.equals(version, that.version) && Objects.equals(xmlns, that.xmlns) && Objects.equals(vars, that.vars) && Objects.equals(forms, that.forms);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, xmlns, vars, forms);
    }
}
