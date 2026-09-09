package me.redot.grin.agent;

import org.jline.builtins.ConfigurationPath;
import org.jline.console.impl.Builtins;
import org.jline.reader.LineReader;
import org.jline.reader.Widget;

import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;

public final class GrinBuiltinsFactory {

    private GrinBuiltinsFactory() {}

    public static Builtins create(
            Supplier<Path> workDir,
            ConfigurationPath configPath,
            LineReader reader,
            Function<String, Widget> widgetCreator
    ) {
        Builtins builtins = new Builtins(workDir, configPath, widgetCreator);
        builtins.setLineReader(reader);
        return builtins;
    }
}
