package me.redot.grin.core;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

class GrinCli {

    public static void main(String[] args) {
        if (args.length == 0) {
            JvmSelector.select().ifPresent(GrinCli::attach);
            return;
        }

        switch (args[0]) {
            case "list" -> listJvms();
            case "attach" -> {
                if (args.length < 2) {
                    System.out.println("Usage: grin attach <pid>");
                    return;
                }
                attach(args[1]);
            }
            default -> printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("""
        
        Groovy Introspective
        
        Usage:
          grin list            List running JVM processes
          grin attach <pid>    Attach grin to a running JVM
        """.stripIndent());
    }

    private static void listJvms() {
        List<VirtualMachineDescriptor> vms = JvmSelector.availableJvms();
        System.out.println();

        if (vms.isEmpty()) {
            System.out.println("No JVM processes found.");
            return;
        }

        System.out.println("Active JVM Processes (PID, display name):");

        for (VirtualMachineDescriptor vm : vms) {
            System.out.println(vm.id()+"\t"+vm.displayName());
        }
    }

    private static void attach(String pid) {
        System.out.println("Attaching to JVM " + pid + "...");
        try {
            String agentJarPath = copyAgentJarToCache();
            int port = findFreePort();
            VirtualMachine vm = VirtualMachine.attach(pid);
            
            try {
                vm.loadAgent(agentJarPath, "port=" + port);
            } finally {
                vm.detach();
            }
            waitForPortOpen(port);
            connectSsh(port);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String copyAgentJarToCache() throws IOException {
        Path cacheDir = Path.of(System.getProperty("user.home"), ".grin", "cache");
        Files.createDirectories(cacheDir);
        Path staged = Files.createTempFile(cacheDir, "grin-agent-", ".tmp");
        MessageDigest digest = sha256();

        try (InputStream resource = GrinCli.class.getResourceAsStream("/agent-1.0.0.jar")) {
            if (resource == null) {
                throw new IllegalArgumentException("agent jar is missing...");
            }

            try (DigestInputStream input = new DigestInputStream(resource, digest)) {
                Files.copy(input, staged, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            Files.deleteIfExists(staged);
            throw exception;
        }

        Path cached = cacheDir.resolve("grin-agent-" + HexFormat.of().formatHex(digest.digest()) + ".jar");
        try {
            if (!Files.exists(cached)) {
                try {
                    try {
                        Files.move(staged, cached, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(staged, cached);
                    }
                } catch (FileAlreadyExistsException ignored) {
                    // another grin process created the same agent
                }
            }
        } finally {
            Files.deleteIfExists(staged);
        }

        return cached.toAbsolutePath().toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    private static int findFreePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void waitForPortOpen(int port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;

        while (System.currentTimeMillis() < deadline) {
            try {
                new Socket("localhost", port).close();
                return;
            } catch (IOException ignored) {
                Thread.sleep(100);
            }
        }

        throw new RuntimeException("Timed out waiting for agent SSHD on port " + port);
    }

    private static void connectSsh(int port) throws IOException, InterruptedException {
        String nullDevice = System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null";
        ProcessBuilder pb = new ProcessBuilder(
                "ssh", "-tt",
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=" + nullDevice,
                "-o", "LogLevel=ERROR",
                "-p", Integer.toString(port),
                "grin@localhost"
        );
        pb.inheritIO();
        Process sshProcess = pb.start();
        sshProcess.waitFor();
    }
}
