package me.redot.grin.core;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

class GrinCli {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
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
        List<VirtualMachineDescriptor> vms = VirtualMachine.list();
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
        try {
            String agentJarPath = copyAgentJarToTemp();
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

    private static String copyAgentJarToTemp() throws IOException {
        try (var input = GrinCli.class.getResourceAsStream("/agent-1.0.0.jar")) {
            if (input == null) {
                throw new IllegalArgumentException("agent jar is missing...");
            }

            Path temp = Files.createTempFile("grin-agent", ".jar");
            temp.toFile().deleteOnExit();
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            return temp.toAbsolutePath().toString();
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
