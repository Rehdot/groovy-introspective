package me.redot.grin.agent

import groovy.transform.CompileStatic
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.UserAuthFactory
import org.apache.sshd.server.auth.UserAuthNoneFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider

import java.nio.file.Path
import java.time.Duration

@CompileStatic
class GrinSession {

    private static SshServer server

    void start(String agentArgs) throws Exception {
        int port = Integer.parseInt(GrinAgent.parseArg(agentArgs, "port"))

        SshServer sshd = SshServer.setUpDefaultServer()
        sshd.setPort(port)
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(
                Path.of(System.getProperty("java.io.tmpdir"), "grin-hostkey.ser")
        ))

        sshd.setHost("127.0.0.1")
        sshd.userAuthFactories = [UserAuthNoneFactory.INSTANCE] as List<UserAuthFactory>
        sshd.setPasswordAuthenticator(null)
        sshd.setPublickeyAuthenticator(null)
        sshd.setKeyboardInteractiveAuthenticator(null)

        sshd.setShellFactory {
            new GroovyshCommand()
        }

        CoreModuleProperties.IDLE_TIMEOUT.set(sshd, Duration.ZERO)
        CoreModuleProperties.NIO2_READ_TIMEOUT.set(sshd, Duration.ZERO)

        replaceServer(sshd)

        System.out.println("[grin-agent] SSHD listening on port " + port)
    }

    private static synchronized void replaceServer(SshServer replacement) throws IOException {
        if (server != null) {
            try {
                server.stop(true)
            } catch (IOException failure) {
                System.err.println("[grin-agent] unable to stop previous SSHD: $failure.message")
            } finally {
                server = null
            }
        }

        replacement.start()
        server = replacement
    }

}
