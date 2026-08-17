package me.redot.grin.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GrinAgent {

    public static final Set<String> RUNTIME_JAR_PATHS = ConcurrentHashMap.newKeySet();
    private static volatile Instrumentation instrumentation;

    public static void agentmain(String agentArgs, Instrumentation inst) throws Exception {
        instrumentation = inst;

        new Thread(GrinAgent::refreshRuntimeJarPaths).start();
        AggregateClassLoader aggregate = new AggregateClassLoader(GrinAgent.class.getClassLoader(), inst);
        System.out.println("[grin-agent] aggregate classloader built from " + inst.getAllLoadedClasses().length + " loaded classes");

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(aggregate);

        try {
            Class<?> sessionClass = aggregate.loadClass("me.redot.grin.agent.GrinSession");
            Object session = sessionClass.getDeclaredConstructor().newInstance();
            sessionClass.getMethod("start", String.class).invoke(session, agentArgs);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    public static void refreshRuntimeJarPaths() {
        Instrumentation inst = instrumentation;
        if (inst == null) return;

        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            try {
                ProtectionDomain domain = clazz.getProtectionDomain();
                if (domain == null) continue;

                CodeSource codeSource = domain.getCodeSource();
                if (codeSource == null) continue;

                URL location = codeSource.getLocation();
                if (location == null) continue;
                if (!"file".equalsIgnoreCase(location.getProtocol())) continue;

                RUNTIME_JAR_PATHS.add(new File(location.toURI()).getCanonicalPath());
            } catch (Throwable ignored) {}
        }
    }

    public static String parseArg(String agentArgs, String key) {
        for (String pair : agentArgs.split(",")) {
            String[] split = pair.trim().split("=", 2);
            if (split.length == 2 && split[0].equals(key)) return split[1];
        }
        return null;
    }
}