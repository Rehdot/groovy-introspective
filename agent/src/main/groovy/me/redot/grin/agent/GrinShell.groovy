package me.redot.grin.agent

import groovy.transform.Canonical
import org.apache.groovy.groovysh.Main
import org.apache.groovy.groovysh.jline.*
import org.apache.sshd.server.Environment
import org.apache.sshd.server.Signal
import org.apache.sshd.server.SignalListener
import org.jline.builtins.ClasspathResourceUtil
import org.jline.builtins.ConfigurationPath
import org.jline.builtins.SyntaxHighlighter
import org.jline.console.impl.DefaultPrinter
import org.jline.console.impl.SystemHighlighter
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.DefaultParser
import org.jline.reader.impl.DefaultParser.Bracket
import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.Size
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.OSUtils
import org.jline.widget.AutosuggestionWidgets
import org.jline.widget.TailTipWidgets

import java.nio.charset.StandardCharsets
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

import static org.jline.jansi.AnsiRenderer.render

@Canonical
class GrinShell {

    static GROOVY_POSIX_CMDS = ['/ls', '/wc', '/sort', '/head', '/tail', '/cat', '/grep']

    final InputStream input
    final OutputStream output
    final Map<String, ?> initialBindings = [:]
    final Environment environment = null

    @SuppressWarnings('GroovyResultOfObjectAllocationIgnored')
    int start() {
        def parser = new DefaultParser(
                regexCommand: /\/?[a-zA-Z!]\S*/,
                eofOnUnclosedQuote: true,
                eofOnEscapedNewLine: true
        )
        parser.blockCommentDelims(new DefaultParser.BlockCommentDelims('/*', '*/'))
                .lineCommentDelims(new String[]{'// '})
                .setEofOnUnclosedBracket(Bracket.CURLY, Bracket.ROUND, Bracket.SQUARE)

        def sshEnvironment = environment?.env ?: [:]
        def terminal = TerminalBuilder.builder()
                .streams(input, output)
                .system(false)
                .name('grin')
                .type(sshEnvironment[Environment.ENV_TERM] ?: 'xterm-256color')
                .size(terminalSize(sshEnvironment))
                .build()

        SignalListener resizeListener
        if (environment != null) {
            resizeListener = new GrinSignalListener(() -> {
                terminal.setSize(terminalSize(environment.env))
                terminal.raise(Terminal.Signal.WINCH)
            })
            environment.addSignalListener(resizeListener, Signal.WINCH)
        }

        def rootURL = Main.getResource('/nanorc')
        def userState = createUserState()
        def root = createConfig(rootURL, userState)
        def configPath = new ConfigurationPath(root, userState)
        def scriptEngine = new GroovyEngine()
        def grin = new GrinApi()

        initialBindings.each { k, v -> if (k != null) scriptEngine.put(k, v) }
        scriptEngine.put('grin', grin)
        scriptEngine.put('ROOT', rootURL.toString())
        scriptEngine.put(GroovyEngine.NANORC_VALUE, rootURL.toString())
        scriptEngine.put('CONSOLE_OPTIONS', [:])
        scriptEngine.put('GROOVYSH_OPTIONS', [interpreterMode: true])

        def printer = new DefaultPrinter(scriptEngine, configPath)

        def readerBuilder = LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(parser)
                .variable(LineReader.SECONDARY_PROMPT_PATTERN, "%M%P > ")
                .variable(LineReader.INDENTATION, 2)
                .variable(LineReader.LIST_MAX, 100)
                .option(LineReader.Option.INSERT_BRACKET, true)
                .option(LineReader.Option.EMPTY_WORD_OPTIONS, false)
                .option(LineReader.Option.USE_FORWARD_SLASH, true)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)

        if (userState) {
            readerBuilder.variable(LineReader.HISTORY_FILE, userState.resolve('history'))
        }

        def reader = (LineReaderImpl) readerBuilder.build()

        if (OSUtils.IS_WINDOWS) {
            reader.setVariable(LineReader.BLINK_MATCHING_PAREN, 0)
        }

        def thread = Thread.currentThread()
        terminal.handle(Terminal.Signal.INT, signal -> thread.interrupt())

        def workDir = Paths.get(System.getProperty('user.dir'))
        def groovy = new GroovyCommands(scriptEngine, { workDir }, printer, null)
        def consoleEngine = new GroovyConsoleEngine(scriptEngine, printer, { workDir }, configPath, reader)
        def builtins = new GroovyBuiltins(scriptEngine, { workDir }, configPath, reader, null)

        def systemRegistry = new GroovySystemRegistry(
                parser,
                terminal,
                { workDir },
                configPath
        ).tap {
            setCommandRegistries(consoleEngine, builtins, groovy)
            groupCommandsInHelp(false)
            setScriptDescription(scriptEngine.&scriptDescription)
            addCompleter(scriptEngine.scriptCompleter)

            renameLocal('exit', '/exit')
            renameLocal('help', '/help')

            invoke('/alias', '/x', '/exit')
            invoke('/alias', '/q', '/exit')
            invoke('/alias', '/h', '/help')

            setConsoleOption("ignoreUnknownPipes", true)
        }

        def jnanorc = root.resolve('jnanorc')
        def commandHighlighter = SyntaxHighlighter.build(jnanorc, "COMMAND")
        def argsHighlighter = SyntaxHighlighter.build(jnanorc, "ARGS")
        def groovyHighlighter = SyntaxHighlighter.build(jnanorc, "Groovy")
        def highlighter = new SystemHighlighter(commandHighlighter, argsHighlighter, groovyHighlighter).tap {
            if (!OSUtils.IS_WINDOWS) {
                setSpecificHighlighter("/!", SyntaxHighlighter.build(jnanorc, "SH-REPL"))
            }
            addFileHighlight('/nano', '/less', '/slurp', '/load', '/save', *GROOVY_POSIX_CMDS, '/cd')
            addFileHighlight('/classloader', null, ['-a', '--add'])
            addExternalHighlighterRefresh(printer::refresh)
            addExternalHighlighterRefresh(scriptEngine::refresh)
        }

        reader.highlighter = highlighter
        reader.completer = systemRegistry.completer()

        new TailTipWidgets(
                reader, systemRegistry.&commandDescription,
                5, TailTipWidgets.TipType.COMPLETER
        )
        new AutosuggestionWidgets(reader).inspect()

        def writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true)
        def dashboard = new GrinDashboard(terminal, grin.header)

        appendRuntimeJarsToClasspath systemRegistry

        writer.println()
        writer.println render('Groovy Introspective', 'green')
        writer.println(render('Type \'')
                + render('/help', 'bold')
                + render('\' for help, \'')
                + render('/exit', 'bold')
                + render('\' to exit.'))
        writer.println()

        boolean liveDashboard = dashboard.start()
        if (!liveDashboard) dashboard.printSnapshot(writer)

        try {
            getScriptFile("attach").ifPresent { scriptEngine.execute(it) }

            while (true) {
                try {
                    systemRegistry.cleanUp()

                    def line = reader.readLine(render('grin', 'green') + render('> '))
                    def result = systemRegistry.execute(line)

                    consoleEngine.println(result?.toString())
                } catch (UserInterruptException ignored) {
                    // ignore
                } catch (EndOfFileException ignored) {
                    break
                } catch (Throwable t) {
                    systemRegistry.trace(t)
                }
            }
        } finally {
            getScriptFile("detach").ifPresent { scriptEngine.execute(it) }
            dashboard.close()
            if (resizeListener != null) environment.removeSignalListener(resizeListener)
            systemRegistry.close()
            terminal.close()
        }
        return 0
    }

    private static Optional<File> getScriptFile(String name) {
        def home = System.getenv('USERPROFILE') ?: System.getProperty('user.home')
        if (!home) return Optional.empty()

        def file = "${home}/.grin/bin/scripts/${name}.groovy" as File
        if (file.exists()) return Optional.of(file)

        Optional.empty()
    }

    private static Path createUserState() {
        String home = System.getenv('USERPROFILE') ?: System.getProperty('user.home')
        if (!home) return null

        try {
            Path state = Paths.get(home, '.grin')
            Files.createDirectories(state)
            return state
        } catch (ignored) {
            return null
        }
    }

    private static Path createConfig(URL sourceUrl, Path userState) {
        Path source = resolveResourcePath(sourceUrl)
        Path destination = userState != null
                ? userState.resolve('runtime/nanorc')
                : Files.createTempDirectory('grin-nanorc-')

        Files.createDirectories(destination)
        def paths = Files.walk(source)
        try {
            paths.forEach { Path path ->
                Path target = destination.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } finally {
            paths.close()
        }
        return destination
    }

    private static Size terminalSize(Map<String, String> environment) {
        int columns = positiveInt(environment[Environment.ENV_COLUMNS], 120)
        int rows = positiveInt(environment[Environment.ENV_LINES], 30)
        return new Size(columns, rows)
    }

    private static int positiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value)
            return parsed > 0 ? parsed : fallback
        } catch (RuntimeException ignored) {
            return fallback
        }
    }

    private static void appendRuntimeJarsToClasspath(GroovySystemRegistry systemRegistry) {
        new Thread({
            GrinAgent.RUNTIME_JAR_PATHS.each { path ->
                try {
                    systemRegistry.invoke('/classloader', "--add=$path")
                } catch (Exception ignored) {}
            }
        }, "grin-classloader-worker").start()
    }

    private static Path resolveResourcePath(URL url) {
        try {
            return ClasspathResourceUtil.getResourcePath(url)
        } catch (FileSystemAlreadyExistsException ignored) {
            def connection = (JarURLConnection) url.openConnection()
            def uri = connection.jarFileURL.toURI()
            def entryPath = '/' + connection.entryName

            def fileSystem = FileSystems.getFileSystem(URI.create("jar:${uri}!/"))
            return fileSystem.getPath(entryPath)
        }
    }

}