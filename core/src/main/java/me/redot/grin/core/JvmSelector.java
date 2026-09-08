package me.redot.grin.core;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import org.jline.keymap.KeyMap;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class JvmSelector {

    private static final AttributedStyle GREEN = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.GREEN);
    private static final AttributedStyle GREEN_BOLD = GREEN.bold();
    private static final AttributedStyle MUTED = AttributedStyle.DEFAULT.faint();

    static Optional<String> select() {
        List<VirtualMachineDescriptor> initial = availableJvms();
        if (initial.isEmpty()) {
            System.out.println("No JVM processes found.");
            return Optional.empty();
        }

        try (Terminal terminal = TerminalBuilder.builder().name("grin").system(true).build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .appName("grin")
                    .terminal(terminal)
                    .completer((ignored, line, candidates) -> addCandidates(terminal, candidates))
                    .option(LineReader.Option.AUTO_LIST, true)
                    .option(LineReader.Option.LIST_ROWS_FIRST, true)
                    .option(LineReader.Option.EMPTY_WORD_OPTIONS, false)
                    .build();
            for (KeyMap<Binding> keyMap : reader.getKeyMaps().values()) {
                keyMap.bind(new Reference(LineReader.MENU_COMPLETE), "\t");
            }

            printIntroduction(terminal, initial.size());

            while (true) {
                String selection;
                try {
                    selection = reader.readLine(prompt(terminal)).trim();
                } catch (UserInterruptException | EndOfFileException ignored) {
                    terminal.writer().println();
                    terminal.flush();
                    return Optional.empty();
                }

                if (selection.isEmpty()) {
                    continue;
                }

                Optional<String> pid = parsePid(selection);
                if (pid.isPresent()) {
                    return pid;
                }

                reader.printAbove(styled(terminal, builder -> builder
                        .styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED), "Enter a PID or press Tab to choose a JVM.")));
            }
        } catch (IOException failure) {
            System.err.println("Unable to open terminal: " + failure.getMessage());
            return Optional.empty();
        }
    }

    public static Optional<String> parsePid(String str) {
        String[] split = str.trim().split("\\D");
        if (split.length < 1) return Optional.empty();

        String first = split[0];
        if (first.chars().allMatch(Character::isDigit)) {
            return Optional.of(first);
        }

        return Optional.empty();
    }

    public static List<VirtualMachineDescriptor> availableJvms() {
        String currentPid = Long.toString(ProcessHandle.current().pid());
        return VirtualMachine.list().stream()
                .filter(vm -> !vm.id().equals(currentPid))
                .sorted(Comparator.comparingLong(JvmSelector::numericPid))
                .toList();
    }

    private static void addCandidates(Terminal terminal, List<Candidate> candidates) {
        int nameWidth = Math.max(16, terminal.getColumns() - 16);
        for (VirtualMachineDescriptor vm : availableJvms()) {
            String display = String.format("%-8s %s", vm.id(), truncate(displayName(vm), nameWidth));
            candidates.add(new Candidate(
                    display,
                    display,
                    null,
                    null,
                    null,
                    null,
                    true,
                    (int) Math.min(numericPid(vm), Integer.MAX_VALUE)
            ));
        }
    }

    private static void printIntroduction(Terminal terminal, int count) {
        PrintWriter writer = terminal.writer();
        writer.println();
        writer.println(styled(terminal, builder -> builder
                .styled(GREEN_BOLD, "Groovy Introspective")));
        writer.println(styled(terminal, builder ->
                builder.append(String.valueOf(count))
                        .append(count == 1 ? " JVM available. " : " JVMs available. ")
                .styled(MUTED, "Type a PID or press Tab to choose.")));
        writer.println();
        terminal.flush();
    }

    private static String prompt(Terminal terminal) {
        return styled(terminal, builder -> builder
                .styled(GREEN_BOLD, "grin")
                .append("> "));
    }

    private static String displayName(VirtualMachineDescriptor vm) {
        String name = vm.displayName().replaceAll("\\p{Cntrl}", " ").trim();
        return name.isEmpty() ? "unknown JVM" : name;
    }

    private static String truncate(String value, int maximumLength) {
        if (value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, Math.max(1, maximumLength - 1)) + "...";
    }

    private static long numericPid(VirtualMachineDescriptor vm) {
        try {
            return Long.parseLong(vm.id());
        } catch (NumberFormatException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static String styled(Terminal terminal, java.util.function.Consumer<AttributedStringBuilder> content) {
        AttributedStringBuilder builder = new AttributedStringBuilder();
        content.accept(builder);
        return builder.toAttributedString().toAnsi(terminal);
    }
}
