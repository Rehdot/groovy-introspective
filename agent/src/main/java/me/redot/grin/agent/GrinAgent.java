package me.redot.grin.agent;

import me.redot.grin.agent.completion.NamespaceClassIndex;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GrinAgent {

    private static final NamespaceClassIndex NAMESPACE_INDEX = new NamespaceClassIndex();
    private static volatile Instrumentation instrumentation;

    public static synchronized void agentmain(String agentArgs, Instrumentation inst) throws Exception {
        instrumentation = inst;

        new Thread(GrinAgent::refreshRuntimeJarPaths).start();

        AggregateClassLoader aggregate = AggregateClassLoader.getInstance();
        if (aggregate == null) {
            aggregate = new AggregateClassLoader(GrinAgent.class.getClassLoader(), inst, NAMESPACE_INDEX);
            AggregateClassLoader.setInstance(aggregate);
        } else {
            aggregate.refreshDelegates();
        }

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

        Set<String> seenLocations = ConcurrentHashMap.newKeySet();
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            try {
                NAMESPACE_INDEX.addClassName(clazz.getName());

                ProtectionDomain domain = clazz.getProtectionDomain();
                if (domain == null) continue;

                CodeSource codeSource = domain.getCodeSource();
                if (codeSource == null) continue;

                URL location = codeSource.getLocation();
                if (location == null || !"file".equalsIgnoreCase(location.getProtocol())
                        || !seenLocations.add(location.toExternalForm())) continue;

                String path = new File(location.toURI()).getCanonicalPath();
                NAMESPACE_INDEX.addPath(Path.of(path));
            } catch (Throwable ignored) {}
        }
    }

    public static NamespaceClassIndex getNamespaceIndex() {
        return NAMESPACE_INDEX;
    }

    public static String parseArg(String agentArgs, String key) {
        for (String pair : agentArgs.split(",")) {
            String[] split = pair.trim().split("=", 2);
            if (split.length == 2 && split[0].equals(key)) return split[1];
        }
        return null;
    }
}