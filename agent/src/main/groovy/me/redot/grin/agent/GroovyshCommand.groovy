package me.redot.grin.agent

import groovy.transform.CompileStatic
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command

@CompileStatic
class GroovyshCommand implements Command, Runnable {

    private InputStream input
    private OutputStream output
    private OutputStream error
    private ExitCallback exitCallback
    private Environment environment
    private Thread worker

    @Override
    void setInputStream(InputStream input) {
        this.input = input
    }

    @Override
    void setOutputStream(OutputStream output) {
        this.output = output
    }

    @Override
    void setErrorStream(OutputStream error) {
        this.error = error
    }

    @Override
    void setExitCallback(ExitCallback callback) {
        this.exitCallback = callback
    }

    @Override
    void start(ChannelSession channel, Environment env) {
        this.environment = env

        this.worker = new Thread(this, 'grin-session')
        this.worker.daemon = true
        this.worker.contextClassLoader = GrinAgent.getShellClassLoader()
        this.worker.start()
    }

    @Override
    void run() {
        int exitCode = 0

        try {
            println "[grin] Starting Groovy shell..."
            def shell = new GrinShell(input, output, [:], environment)
            exitCode = shell.start()
            println "[grin] Groovy shell returned: $exitCode"
        } catch (Throwable t) {
            System.err.println("[grin] Groovy shell crashed:")
            t.printStackTrace()
            t.printStackTrace(new PrintStream(this.error, true))
            exitCode = 1
        } finally {
            println "[grin] Closing SSH shell..."
            exitCallback.onExit(exitCode)
        }
    }

    @Override
    void destroy(ChannelSession channel) {
        this.worker?.interrupt()
    }
}