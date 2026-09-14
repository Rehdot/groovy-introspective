package me.redot.grin.agent

import groovy.transform.Canonical
import me.redot.grin.agent.completion.ImportSuggestionCompleter
import me.redot.grin.agent.completion.NamespaceClassCompleter
import org.apache.groovy.groovysh.Main
import org.apache.groovy.groovysh.jline.GroovyCommands
import org.apache.groovy.groovysh.jline.GroovyConsoleEngine
import org.apache.groovy.groovysh.jline.GroovyEngine
import org.apache.groovy.groovysh.jline.GroovySystemRegistry
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
import org.jline.terminal.impl.ExternalTerminal
import org.jline.utils.OSUtils

import java.nio.charset.StandardCharsets
import java.nio.file.*

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
        def terminal = new ExternalTerminal(
                'grin',
                sshEnvironment[Environment.ENV_TERM] ?: 'xterm-256color',
                input,
                output,
                StandardCharsets.UTF_8
        )
        terminal.setSize(terminalSize(sshEnvironment))

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
        def root = resolveResourcePath(rootURL)
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
        def builtins = GrinBuiltinsFactory.create({ workDir }, configPath, reader, null)

        ImportSuggestionCompleter importSuggestions
        def systemRegistry = new GroovySystemRegistry(
                parser,
                terminal,
                { workDir },
                configPath
        ).tap {
            setCommandRegistries(consoleEngine, builtins, groovy)
            groupCommandsInHelp(false)
            setScriptDescription(scriptEngine.&scriptDescription)
            importSuggestions = new ImportSuggestionCompleter(scriptEngine.scriptCompleter)
            addCompleter(importSuggestions)
            addCompleter(new NamespaceClassCompleter(GrinAgent.getNamespaceIndex()))

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

        def writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true)
        def dashboard = new GrinDashboard(terminal, grin.header)

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

                    def prompt = render('grin', 'green') + render('> ')
                    def initialBuffer = importSuggestions.initialBuffer()
                    def line
                    try {
                        line = initialBuffer == null
                                ? reader.readLine(prompt)
                                : reader.readLine(prompt, null as Character, initialBuffer)
                    } finally {
                        importSuggestions.clear()
                    }
                    def result = systemRegistry.execute(line)

                    consoleEngine.println(result?.toString())
                } catch (UserInterruptException ignored) {
                    importSuggestions.clear()
                } catch (EndOfFileException ignored) {
                    importSuggestions.clear()
                    break
                } catch (Throwable t) {
                    systemRegistry.trace(t)
                    suggestImport(t, importSuggestions, writer)
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

    private static void suggestImport(Throwable failure, ImportSuggestionCompleter suggestions, PrintWriter writer) {
        def missing = findMissingProperty(failure)
        if (missing == null || missing.type == null || !Script.isAssignableFrom(missing.type)) return

        String simpleName = missing.property
        if (!isIdentifier(simpleName)) return

        def matches = GrinAgent.namespaceIndex.classesNamed(simpleName)
        if (matches.isEmpty()) return

        def aggregate = AggregateClassLoader.instance
        def ranked = aggregate == null ? matches.sort() : aggregate.rankClassNames(matches)

        writer.println("Found ${ranked.size()} type${ranked.size() == 1 ? "" : "s"} matching '${simpleName}'. Press Tab to choose:")

        int displayed = Math.min(8, ranked.size())
        for (int i = 0; i < displayed; i++) {
            writer.println("  ${ranked[i]}")
        }

        if (ranked.size() > displayed) {
            writer.println("  ... and ${ranked.size() - displayed} more")
        }

        suggestions.suggest(ranked)
    }

    private static MissingPropertyException findMissingProperty(Throwable failure) {
        def seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>())

        for (Throwable current = failure; current != null && seen.add(current); current = current.cause) {
            if (current instanceof MissingPropertyException) return current
        }

        return null
    }

    private static boolean isIdentifier(String value) {
        if (value == null || value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) return false

        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) return false
        }

        return true
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