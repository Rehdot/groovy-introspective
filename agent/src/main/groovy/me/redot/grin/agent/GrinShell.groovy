package me.redot.grin.agent

import groovy.transform.Canonical
import org.apache.groovy.groovysh.Main
import org.apache.groovy.groovysh.jline.*
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
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.OSUtils
import org.jline.widget.AutosuggestionWidgets
import org.jline.widget.TailTipWidgets

import java.nio.charset.StandardCharsets
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths

import static org.jline.jansi.AnsiRenderer.render

@Canonical
class GrinShell {

    static GROOVY_POSIX_CMDS = ['/ls', '/wc', '/sort', '/head', '/tail', '/cat', '/grep']

    final InputStream input
    final OutputStream output
    final Map<String, ?> initialBindings = [:]

    @SuppressWarnings('GroovyResultOfObjectAllocationIgnored')
    int start() {
        def parser = new DefaultParser(
                regexCommand: /\/?[a-zA-Z!]\S*/,
                eofOnUnclosedQuote: true,
                eofOnEscapedNewLine: true
        )
        parser.blockCommentDelims(new DefaultParser.BlockCommentDelims('/*', '*/'))
                .lineCommentDelims(new String[]{'//'})
                .setEofOnUnclosedBracket(Bracket.CURLY, Bracket.ROUND, Bracket.SQUARE)

        def terminal = TerminalBuilder.builder()
                .streams(input, output)
                .system(false)
                .name('grin')
                .build()

        def rootURL = Main.getResource('/nanorc')
        def root = resolveResourcePath(rootURL)
        def userState = Paths.get(System.getProperty('user.home'), '.groovy')
        def configPath = new ConfigurationPath(root, userState)
        def scriptEngine = new GroovyEngine()

        initialBindings.each { k, v -> if (k != null) scriptEngine.put(k, v) }
        scriptEngine.put('ROOT', rootURL.toString())
        scriptEngine.put(GroovyEngine.NANORC_VALUE, rootURL.toString())
        scriptEngine.put('CONSOLE_OPTIONS', [:])
        scriptEngine.put('GROOVYSH_OPTIONS', [interpreterMode: true])

        def printer = new DefaultPrinter(scriptEngine, configPath)

        def reader = (LineReaderImpl) LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(parser)
                .variable(LineReader.SECONDARY_PROMPT_PATTERN, "%M%P > ")
                .variable(LineReader.INDENTATION, 2)
                .variable(LineReader.LIST_MAX, 100)
                .variable(LineReader.HISTORY_FILE, configPath.getUserConfig('groovysh_history', true))
                .option(LineReader.Option.INSERT_BRACKET, true)
                .option(LineReader.Option.EMPTY_WORD_OPTIONS, false)
                .option(LineReader.Option.USE_FORWARD_SLASH, true)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .build()

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
        new AutosuggestionWidgets(reader)

        def writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true)

        new Thread({
            GrinAgent.RUNTIME_JAR_PATHS.each { path ->
                try {
                    systemRegistry.invoke('/classloader', "--add=$path")
                } catch (Exception ignored) {
                    writer.println render("Failed to append $path to the classpath!")
                }
            }
        }, "grin-classloader-worker").start()

        writer.println()
        writer.println render('Groovy Introspective', 'green')
        writer.println(render('JVM: ') + render("${Runtime.version()}", 'bold'))
        writer.println(render('Type \'')
                + render('/help', 'bold')
                + render('\' for help, \'')
                + render('/exit', 'bold')
                + render('\' to exit.'))
        writer.println()

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
        systemRegistry.close()
        return 0
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