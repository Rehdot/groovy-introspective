package me.redot.grin.agent.completion;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

public final class NamespaceClassCompleter implements Completer {

    private final NamespaceClassIndex index;

    public NamespaceClassCompleter(NamespaceClassIndex index) {
        this.index = index;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word().substring(0, line.wordCursor());
        int expressionStart = expressionStart(word);
        String leading = word.substring(0, expressionStart);
        String expression = word.substring(expressionStart);
        if (expression.isEmpty()) return;

        int lastDot = expression.lastIndexOf('.');
        String packageName = lastDot < 0 ? "" : expression.substring(0, lastDot);
        String prefix = lastDot < 0 ? expression : expression.substring(lastDot + 1);
        String qualifiedPrefix = packageName.isEmpty() ? "" : packageName + ".";

        for (String child : this.index.packageChildren(packageName)) {
            if (child.startsWith(prefix)) {
                candidates.add(new Candidate(
                        leading + qualifiedPrefix + child + ".",
                        child,
                        "Packages",
                        null, null, null,
                        false));
            }
        }

        for (String child : this.index.classChildren(packageName)) {
            if (child.startsWith(prefix)) {
                candidates.add(new Candidate(
                        leading + qualifiedPrefix + child,
                        child,
                        "Classes",
                        null, null, null,
                        false));
            }
        }
    }

    private static int expressionStart(String word) {
        int index = word.length();
        while (index > 0) {
            char ch = word.charAt(index - 1);
            if (ch != '.' && !Character.isJavaIdentifierPart(ch)) break;
            index--;
        }
        return index;
    }
}