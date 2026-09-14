package me.redot.grin.agent.completion;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class ImportSuggestionCompleter implements Completer {

    private final Completer delegate;
    private volatile List<String> suggestions = Collections.emptyList();

    public ImportSuggestionCompleter(Completer delegate) {
        this.delegate = delegate;
    }

    public void suggest(Collection<String> classNames) {
        this.suggestions = List.copyOf(classNames);
    }

    public String initialBuffer() {
        return this.suggestions.isEmpty() ? null : "import ";
    }

    public void clear() {
        this.suggestions = Collections.emptyList();
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        List<String> current = this.suggestions;
        String beforeCursor = line.line().substring(0, line.cursor());
        if (current.isEmpty() || !beforeCursor.startsWith("import ")) {
            if (this.delegate != null) this.delegate.complete(reader, line, candidates);
            return;
        }

        for (int i = 0; i < current.size(); i++) {
            String className = current.get(i);
            candidates.add(
                    new Candidate(
                            className, className, "Matching imports",
                            null, null, null, true, i
                    )
            );
        }
    }
}
